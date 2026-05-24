package com.hkfcl.world

data class User(val id: String, val name: String)

data class Persona(
    val id: String,
    val name: String,
    val description: String,
    val memory: String
)

data class ChatSession(
    val id: String,
    val title: String,
    val personaId: String,
    val createdBy: String
)

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val senderId: String,
    val text: String
)

data class Feature(
    val id: String,
    val title: String,
    val status: String
)

data class DistanceState(
    val available: Boolean,
    val kilometers: Double?
)

enum class Tab(val label: String) {
    Chat("聊天"),
    Distance("距离"),
    World("小世界"),
    Settings("设置")
}
