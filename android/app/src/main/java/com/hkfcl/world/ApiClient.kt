package com.hkfcl.world

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val API_BASE_URL = "https://hkf-cl-world.flworld.workers.dev"

class ApiClient(
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
                createdBy = it.getString("createdBy"),
                updatedAt = it.optString("updatedAt")
            )
        }
    }

    suspend fun createSession(title: String, personaId: String): ChatSession {
        val item = request(
            "POST",
            "/chat/sessions",
            JSONObject().put("title", title).put("personaId", personaId)
        ).getJSONObject("session")
        return ChatSession(
            item.getString("id"),
            item.getString("title"),
            item.getString("personaId"),
            item.getString("createdBy"),
            item.optString("updatedAt")
        )
    }

    suspend fun messages(sessionId: String): List<ChatMessage> {
        val items = request("GET", "/chat/sessions/$sessionId/messages").getJSONArray("messages")
        return items.mapObjects { it.toChatMessage() }
    }

    suspend fun sendMessage(sessionId: String, text: String): List<ChatMessage> {
        val items = request("POST", "/chat/sessions/$sessionId/messages", JSONObject().put("text", text)).getJSONArray("messages")
        return items.mapObjects { it.toChatMessage() }
    }

    suspend fun streamMessage(
        sessionId: String,
        text: String,
        onUserMessage: (ChatMessage) -> Unit,
        onChunk: (String) -> Unit,
        onDone: (ChatMessage, String?) -> Unit
    ) = withContext(Dispatchers.IO) {
        val connection = openConnection("POST", "/chat/sessions/$sessionId/messages/stream", true)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().put("text", text).toString())
        }
        if (connection.responseCode !in 200..299) {
            val errorText = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error(JSONObject(errorText.ifBlank { "{}" }).optString("message", "发送失败"))
        }
        var completed = false
        try {
            connection.inputStream.bufferedReader().use { reader ->
                readEventStream(reader) { event, data ->
                    when (event) {
                        "user" -> onUserMessage(data.getJSONObject("message").toChatMessage())
                        "chunk" -> onChunk(data.optString("text"))
                        "done" -> {
                            completed = true
                            onDone(data.getJSONObject("message").toChatMessage(), data.optString("title").ifBlank { null })
                        }
                        "error" -> error(data.optString("message", "回复生成失败"))
                    }
                }
            }
        } catch (error: IOException) {
            if (!completed || error.message?.contains("unexpected end of stream", ignoreCase = true) != true) throw error
        }
    }

    suspend fun notes(): List<Note> {
        val items = request("GET", "/notes").getJSONArray("notes")
        return items.mapObjects { it.toNote() }
    }

    suspend fun createNote(text: String): Note {
        val item = request("POST", "/notes", JSONObject().put("text", text)).getJSONObject("note")
        return item.toNote()
    }

    suspend fun markNoteRead(id: String) {
        request("POST", "/notes/$id/read")
    }

    suspend fun calendarEvents(month: String? = null): List<CalendarEvent> {
        val path = if (month == null) "/calendar/events" else "/calendar/events?month=$month"
        val items = request("GET", path).getJSONArray("events")
        return items.mapObjects { it.toCalendarEvent() }
    }

    suspend fun createCalendarEvent(date: String, title: String, note: String): CalendarEvent {
        val item = request(
            "POST",
            "/calendar/events",
            JSONObject().put("date", date).put("title", title).put("note", note)
        ).getJSONObject("event")
        return item.toCalendarEvent()
    }

    suspend fun updateCalendarEvent(id: String, date: String, title: String, note: String): CalendarEvent {
        val item = request(
            "PUT",
            "/calendar/events/$id",
            JSONObject().put("date", date).put("title", title).put("note", note)
        ).getJSONObject("event")
        return item.toCalendarEvent()
    }

    suspend fun deleteCalendarEvent(id: String) {
        request("DELETE", "/calendar/events/$id")
    }

    suspend fun album(): Pair<List<AlbumItem>, AlbumQuotaState> {
        val json = request("GET", "/album")
        val quota = json.getJSONObject("quota")
        return json.getJSONArray("items").mapObjects { it.toAlbumItem() } to AlbumQuotaState(
            usedBytes = quota.getLong("usedBytes"),
            limitBytes = quota.getLong("limitBytes")
        )
    }

    suspend fun uploadAlbumItem(fileName: String, mimeType: String, bytes: ByteArray, dataBase64: String): AlbumItem {
        val item = request(
            "POST",
            "/album",
            JSONObject()
                .put("fileName", fileName)
                .put("mimeType", mimeType)
                .put("byteSize", bytes.size)
                .put("dataBase64", dataBase64)
        ).getJSONObject("item")
        return item.toAlbumItem()
    }

    suspend fun albumItem(id: String): AlbumItem {
        val item = request("GET", "/album/$id").getJSONObject("item")
        return item.toAlbumItem()
    }

    suspend fun renameAlbumItem(id: String, name: String): AlbumItem {
        val item = request("PUT", "/album/$id/name", JSONObject().put("name", name)).getJSONObject("item")
        return item.toAlbumItem()
    }

    suspend fun deleteAlbumItem(id: String) {
        request("DELETE", "/album/$id")
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
            val connection = openConnection(method, path, authenticated)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) error(JSONObject(responseText).optString("message", "请求失败"))
            JSONObject(responseText)
        }

    private fun openConnection(method: String, path: String, authenticated: Boolean): HttpURLConnection {
        val connection = URL(API_BASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json")
        if (authenticated) connection.setRequestProperty("Authorization", "Bearer ${token.orEmpty()}")
        return connection
    }
}

private fun readEventStream(reader: BufferedReader, onEvent: (String, JSONObject) -> Unit) {
    var event = "message"
    val dataLines = mutableListOf<String>()
    while (true) {
        val line = reader.readLine() ?: break
        when {
            line.isBlank() -> {
                if (dataLines.isNotEmpty()) {
                    onEvent(event, JSONObject(dataLines.joinToString("\n")))
                    dataLines.clear()
                }
                event = "message"
            }
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> dataLines += line.removePrefix("data:").trim()
        }
    }
}

private fun JSONObject.toChatMessage(): ChatMessage =
    ChatMessage(
        id = getString("id"),
        sessionId = getString("sessionId"),
        role = getString("role"),
        senderId = getString("senderId"),
        text = getString("text"),
        createdAt = optString("createdAt")
    )

private fun JSONObject.toNote(): Note =
    Note(
        id = getString("id"),
        authorId = getString("authorId"),
        text = getString("text"),
        createdAt = getString("createdAt"),
        readAt = optNullableString("readAt")
    )

private fun JSONObject.toCalendarEvent(): CalendarEvent =
    CalendarEvent(
        id = getString("id"),
        date = getString("date"),
        title = getString("title"),
        note = optString("note"),
        createdBy = getString("createdBy")
    )

private fun JSONObject.toAlbumItem(): AlbumItem =
    AlbumItem(
        id = getString("id"),
        uploaderId = getString("uploaderId"),
        mediaType = getString("mediaType"),
        mimeType = getString("mimeType"),
        fileName = getString("fileName"),
        byteSize = getLong("byteSize"),
        createdAt = getString("createdAt"),
        dataBase64 = optNullableString("dataBase64")
    )

private fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name) else null

private fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> =
    (0 until length()).map { index -> block(getJSONObject(index)) }
