package com.hkfcl.world

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ApiClient(
    private val baseUrl: String,
    private val token: String?
) {
    suspend fun login(userId: String, code: String): Pair<String, User> {
        val json = request("POST", "/auth/login", JSONObject().put("userId", userId).put("code", code), false)
        val user = json.getJSONObject("user")
        return json.getString("token") to User(user.getString("id"), user.getString("name"))
    }

    suspend fun features(): List<Feature> {
        val items = request("GET", "/features").getJSONArray("features")
        return items.mapObjects { Feature(it.getString("id"), it.getString("title"), it.getString("status")) }
    }

    suspend fun personas(): List<Persona> {
        val items = request("GET", "/bot/personas").getJSONArray("personas")
        return items.mapObjects {
            Persona(
                id = it.getString("id"),
                name = it.getString("name"),
                description = it.getString("description"),
                memory = it.optString("memory")
            )
        }
    }

    suspend fun createPersona(name: String, description: String, memory: String): Persona {
        val item = request(
            "POST",
            "/bot/personas",
            JSONObject().put("name", name).put("description", description).put("memory", memory)
        ).getJSONObject("persona")
        return Persona(item.getString("id"), item.getString("name"), item.getString("description"), item.optString("memory"))
    }

    suspend fun sessions(): List<ChatSession> {
        val items = request("GET", "/chat/sessions").getJSONArray("sessions")
        return items.mapObjects {
            ChatSession(
                id = it.getString("id"),
                title = it.getString("title"),
                personaId = it.getString("personaId"),
                createdBy = it.getString("createdBy")
            )
        }
    }

    suspend fun createSession(title: String, personaId: String): ChatSession {
        val item = request(
            "POST",
            "/chat/sessions",
            JSONObject().put("title", title).put("personaId", personaId)
        ).getJSONObject("session")
        return ChatSession(item.getString("id"), item.getString("title"), item.getString("personaId"), item.getString("createdBy"))
    }

    suspend fun messages(sessionId: String): List<ChatMessage> {
        val items = request("GET", "/chat/sessions/$sessionId/messages").getJSONArray("messages")
        return items.mapObjects {
            ChatMessage(
                id = it.getString("id"),
                sessionId = it.getString("sessionId"),
                role = it.getString("role"),
                senderId = it.getString("senderId"),
                text = it.getString("text")
            )
        }
    }

    suspend fun sendMessage(sessionId: String, text: String): List<ChatMessage> {
        val items = request("POST", "/chat/sessions/$sessionId/messages", JSONObject().put("text", text)).getJSONArray("messages")
        return items.mapObjects {
            ChatMessage(
                id = it.getString("id"),
                sessionId = it.getString("sessionId"),
                role = it.getString("role"),
                senderId = it.getString("senderId"),
                text = it.getString("text")
            )
        }
    }

    suspend fun updateLocation(latitude: Double, longitude: Double) {
        request("POST", "/location/update", JSONObject().put("latitude", latitude).put("longitude", longitude))
    }

    suspend fun distance(): DistanceState {
        val json = request("GET", "/location/distance")
        return DistanceState(json.optBoolean("available"), if (json.has("kilometers")) json.getDouble("kilometers") else null)
    }

    private suspend fun request(method: String, path: String, body: JSONObject? = null, authenticated: Boolean = true): JSONObject =
        withContext(Dispatchers.IO) {
            val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (authenticated) connection.setRequestProperty("Authorization", "Bearer ${token.orEmpty()}")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) error(JSONObject(text).optString("message", "请求失败"))
            JSONObject(text)
        }
}

private fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> =
    (0 until length()).map { index -> block(getJSONObject(index)) }
