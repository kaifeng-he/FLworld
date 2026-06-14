package com.hkfcl.world

data class User(val id: String, val name: String)

data class Persona(
    val id: String,
    val name: String,
    val description: String,
    val memory: String,
    val bubbleColor: String = "#FFE0A8"
)

data class ChatSession(
    val id: String,
    val title: String,
    val personaId: String,
    val createdBy: String,
    val updatedAt: String = ""
)

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: String,
    val senderId: String,
    val text: String,
    val createdAt: String = ""
)

data class Feature(
    val id: String,
    val title: String,
    val status: String
)

data class DistanceState(
    val available: Boolean,
    val kilometers: Double?,
    val mine: LocationSummary? = null,
    val other: LocationSummary? = null
)

data class LocationSummary(
    val userId: String,
    val name: String,
    val province: String,
    val city: String,
    val updatedAt: String
)

data class NotesState(val notes: List<Note>, val unreadCount: Int)

data class Note(
    val id: String,
    val authorId: String,
    val text: String,
    val createdAt: String,
    val readAt: String?,
    val revision: Int = 1
)

data class CalendarEvent(
    val id: String,
    val date: String,
    val title: String,
    val note: String,
    val createdBy: String,
    val revision: Int = 1
)

data class AlbumItem(
    val id: String,
    val uploaderId: String,
    val mediaType: String,
    val mimeType: String,
    val fileName: String,
    val byteSize: Long,
    val createdAt: String,
    val previewBase64: String? = null,
    val dataBase64: String? = null,
    val revision: Int = 1
)

data class AlbumQuotaState(
    val usedBytes: Long,
    val limitBytes: Long
)

data class MemoryDocument(
    val id: String,
    val title: String,
    val content: String,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
    val revision: Int
)

data class AiMemory(
    val id: String,
    val kind: String,
    val content: String,
    val sourceType: String,
    val sourceIds: List<String> = emptyList(),
    val sourceLabel: String = "",
    val generatedAt: String,
    val updatedAt: String,
    val editedByUser: Boolean,
    val revision: Int
)

enum class Tab(val label: String) {
    Chat("聊天"),
    World("小世界"),
    Mine("我的")
}
