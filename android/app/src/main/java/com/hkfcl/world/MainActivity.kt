package com.hkfcl.world

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WorldTheme {
                WorldApp()
            }
        }
    }
}

@Composable
fun WorldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFFC84B6A),
            secondary = Color(0xFF8B6FAD),
            tertiary = Color(0xFF3F8F86),
            background = Color(0xFFFFF7F8),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFFFE8EE)
        ),
        content = content
    )
}

@Composable
fun WorldApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("world", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val locationHelper = remember { LocationHelper(context) }

    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var userId by remember { mutableStateOf(prefs.getString("userId", "hkf") ?: "hkf") }
    var userName by remember { mutableStateOf(prefs.getString("userName", displayName(userId)) ?: displayName(userId)) }
    var code by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(Tab.World) }
    var activeWorldPage by remember { mutableStateOf<WorldPage?>(null) }
    var personas by remember { mutableStateOf(emptyList<Persona>()) }
    var sessions by remember { mutableStateOf(emptyList<ChatSession>()) }
    var selectedSession by remember { mutableStateOf<ChatSession?>(null) }
    var messages by remember { mutableStateOf(emptyList<ChatMessage>()) }
    var distance by remember { mutableStateOf<DistanceState?>(null) }
    var notes by remember { mutableStateOf(emptyList<Note>()) }
    var calendarEvents by remember { mutableStateOf(emptyList<CalendarEvent>()) }
    var albumItems by remember { mutableStateOf(emptyList<AlbumItem>()) }
    var albumQuota by remember { mutableStateOf<AlbumQuotaState?>(null) }
    var status by remember { mutableStateOf("") }
    val api = remember(token) { ApiClient(token) }

    fun refreshAll() {
        scope.launch {
            runCatching {
                personas = api.personas()
                sessions = api.sessions()
                distance = api.distance()
                notes = api.notes()
                calendarEvents = api.calendarEvents()
                val album = api.album()
                albumItems = album.first
                albumQuota = album.second
                status = ""
            }.onFailure { status = it.message ?: "同步失败" }
        }
    }

    fun refreshAlbum() {
        scope.launch {
            runCatching { api.album() }
                .onSuccess {
                    albumItems = it.first
                    albumQuota = it.second
                    status = ""
                }
                .onFailure { status = it.message ?: "相册同步失败" }
        }
    }

    fun syncLocation() {
        scope.launch {
            val location = locationHelper.currentCoarseLocation()
            if (location == null) {
                status = "定位暂时不可用，稍后再试试"
                return@launch
            }
            runCatching {
                api.updateLocation(location.first, location.second)
                distance = api.distance()
                status = ""
            }.onFailure { status = it.message ?: "距离更新失败" }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) syncLocation() else status = "没有定位权限，暂时不能显示距离"
    }

    LaunchedEffect(token) {
        if (token != null) {
            refreshAll()
            if (locationHelper.hasPermission()) syncLocation() else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    if (token == null) {
        LoginScreen(
            selectedUserId = userId,
            code = code,
            status = status,
            onUserId = { userId = it },
            onCode = { code = it },
            onLogin = {
                scope.launch {
                    runCatching { api.login(userId, code.ifBlank { userId }) }
                        .onSuccess { result ->
                            token = result.first
                            userId = result.second.id
                            userName = result.second.name
                            prefs.edit()
                                .putString("token", result.first)
                                .putString("userId", result.second.id)
                                .putString("userName", result.second.name)
                                .apply()
                            status = ""
                        }
                        .onFailure { status = it.message ?: "登录失败" }
                }
            }
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            tab = item
                            activeWorldPage = null
                        },
                        label = { Text(item.label) },
                        icon = { Text(tabIcon(item)) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(16.dp)
        ) {
            AppHeader(status)
            Spacer(Modifier.height(12.dp))
            when (tab) {
                Tab.Chat -> ChatScreen(
                    currentUserId = userId,
                    personas = personas,
                    sessions = sessions,
                    selectedSession = selectedSession,
                    messages = messages,
                    onRefresh = { refreshAll() },
                    onSelectSession = { session ->
                        selectedSession = session
                        scope.launch {
                            runCatching { api.messages(session.id) }
                                .onSuccess { messages = it }
                                .onFailure { status = it.message ?: "加载聊天失败" }
                        }
                    },
                    onBackToSessions = {
                        selectedSession = null
                        messages = emptyList()
                    },
                    onCreatePersona = { name, desc, memory ->
                        scope.launch {
                            runCatching { api.createPersona(name, desc, memory) }
                                .onSuccess { personas = personas + it }
                                .onFailure { status = it.message ?: "保存聊天风格失败" }
                        }
                    },
                    onCreateSession = { personaId ->
                        scope.launch {
                            runCatching { api.createSession("新的聊天", personaId) }
                                .onSuccess {
                                    selectedSession = it
                                    sessions = listOf(it) + sessions
                                    messages = emptyList()
                                }
                                .onFailure { status = it.message ?: "新建聊天失败" }
                        }
                    },
                    onSend = { text ->
                        val session = selectedSession ?: return@ChatScreen
                        val pendingBotId = "local-bot-${System.currentTimeMillis()}"
                        messages = messages + ChatMessage("local-user", session.id, "user", userId, text) +
                            ChatMessage(pendingBotId, session.id, "assistant", "bot", "")
                        scope.launch {
                            runCatching {
                                api.streamMessage(
                                    session.id,
                                    text,
                                    onUserMessage = {},
                                    onChunk = { chunk ->
                                        scope.launch {
                                            messages = messages.map {
                                                if (it.id == pendingBotId) it.copy(text = it.text + chunk) else it
                                            }
                                        }
                                    },
                                    onDone = { assistant, title ->
                                        scope.launch {
                                            messages = api.messages(session.id)
                                            sessions = api.sessions()
                                            selectedSession = selectedSession?.copy(title = title ?: selectedSession?.title.orEmpty())
                                        }
                                    }
                                )
                            }.onFailure { status = it.message ?: "发送失败" }
                        }
                    }
                )

                Tab.World -> when (activeWorldPage) {
                    null -> WorldHomeScreen(
                        distance = distance,
                        notes = notes,
                        calendarEvents = calendarEvents,
                        albumQuota = albumQuota,
                        onOpen = { activeWorldPage = it },
                        onRefreshDistance = {
                            if (locationHelper.hasPermission()) syncLocation() else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    )
                    WorldPage.Notes -> NotesScreen(
                        currentUserId = userId,
                        notes = notes,
                        onBack = { activeWorldPage = null },
                        onCreate = { text ->
                            scope.launch {
                                runCatching { api.createNote(text) }
                                    .onSuccess {
                                        notes = listOf(it) + notes
                                        status = ""
                                    }
                                    .onFailure { status = it.message ?: "留言失败" }
                            }
                        },
                        onMarkRead = { note ->
                            if (note.authorId != userId && note.readAt == null) {
                                scope.launch { runCatching { api.markNoteRead(note.id) } }
                            }
                        }
                    )
                    WorldPage.Calendar -> CalendarScreen(
                        events = calendarEvents,
                        onBack = { activeWorldPage = null },
                        onCreate = { date, title, note ->
                            scope.launch {
                                runCatching { api.createCalendarEvent(date, title, note) }
                                    .onSuccess {
                                        calendarEvents = (calendarEvents + it).sortedBy { item -> item.date }
                                        status = ""
                                    }
                                    .onFailure { status = it.message ?: "保存日历失败" }
                            }
                        },
                        onUpdate = { id, date, title, note ->
                            scope.launch {
                                runCatching { api.updateCalendarEvent(id, date, title, note) }
                                    .onSuccess { updated ->
                                        calendarEvents = calendarEvents.map { if (it.id == updated.id) updated else it }.sortedBy { it.date }
                                        status = ""
                                    }
                                    .onFailure { status = it.message ?: "更新日历失败" }
                            }
                        },
                        onDelete = { event ->
                            scope.launch {
                                runCatching { api.deleteCalendarEvent(event.id) }
                                    .onSuccess {
                                        calendarEvents = calendarEvents.filterNot { it.id == event.id }
                                        status = ""
                                    }
                                    .onFailure { status = it.message ?: "删除日历失败" }
                            }
                        }
                    )
                    WorldPage.Album -> AlbumScreen(
                        items = albumItems,
                        quota = albumQuota,
                        onBack = { activeWorldPage = null },
                        onUpload = { name, mimeType, bytes, base64 ->
                            scope.launch {
                                runCatching { api.uploadAlbumItem(name, mimeType, bytes, base64) }
                                    .onSuccess { refreshAlbum() }
                                    .onFailure { status = it.message ?: "上传失败" }
                            }
                        },
                        onDelete = { item ->
                            scope.launch {
                                runCatching { api.deleteAlbumItem(item.id) }
                                    .onSuccess { refreshAlbum() }
                                    .onFailure { status = it.message ?: "删除失败" }
                            }
                        },
                        onRename = { item, name ->
                            scope.launch {
                                runCatching { api.renameAlbumItem(item.id, name) }
                                    .onSuccess { renamed ->
                                        albumItems = albumItems.map { if (it.id == renamed.id) renamed else it }
                                        status = ""
                                    }
                                    .onFailure { status = it.message ?: "改名失败" }
                            }
                        },
                        onLoadItem = { id, onLoaded ->
                            scope.launch {
                                runCatching { api.albumItem(id) }
                                    .onSuccess { onLoaded(it) }
                                    .onFailure { status = it.message ?: "加载失败" }
                            }
                        },
                        onSaveImage = { item ->
                            scope.launch {
                                runCatching { saveImageToGallery(context, item) }
                                    .onSuccess { status = "已保存到手机相册" }
                                    .onFailure { status = it.message ?: "保存失败" }
                            }
                        }
                    )
                }

                Tab.Mine -> MineScreen(
                    userName = userName,
                    personas = personas,
                    onCreatePersona = { name, desc, memory ->
                        scope.launch {
                            runCatching { api.createPersona(name, desc, memory) }
                                .onSuccess { personas = personas + it }
                                .onFailure { status = it.message ?: "保存聊天风格失败" }
                        }
                    },
                    onLogout = {
                        prefs.edit().clear().apply()
                        token = null
                        selectedSession = null
                        messages = emptyList()
                    }
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(
    selectedUserId: String,
    code: String,
    status: String,
    onUserId: (String) -> Unit,
    onCode: (String) -> Unit,
    onLogin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(softBrush())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("FL小世界", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color(0xFF7C3348))
        Text("只属于两个人的陪伴、记录和日常。", color = Color(0xFF7B626A), modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            IdentityButton("锋宝", selectedUserId == "hkf", Modifier.weight(1f)) { onUserId("hkf") }
            IdentityButton("璐宝", selectedUserId == "cl", Modifier.weight(1f)) { onUserId("cl") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = code,
            onValueChange = onCode,
            label = { Text("进入口令") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("进入小世界") }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun IdentityButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else Color(0xFF8B737A)
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(text, color = color, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun AppHeader(status: String) {
    Column {
        Text("FL小世界", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF7C3348))
        Text("把想说的话、重要日子和靠近彼此的瞬间放在一起。", color = Color(0xFF6F5F66))
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ChatScreen(
    currentUserId: String,
    personas: List<Persona>,
    sessions: List<ChatSession>,
    selectedSession: ChatSession?,
    messages: List<ChatMessage>,
    onRefresh: () -> Unit,
    onSelectSession: (ChatSession) -> Unit,
    onBackToSessions: () -> Unit,
    onCreatePersona: (String, String, String) -> Unit,
    onCreateSession: (String) -> Unit,
    onSend: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var selectedPersona by remember(personas) { mutableStateOf(personas.firstOrNull()?.id.orEmpty()) }
    var showPersonaForm by remember { mutableStateOf(false) }

    if (selectedSession == null) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text("今天想聊点什么？", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("可以换一种聊天风格，再开始新的对话。", color = Color(0xFF7B626A))
                    Spacer(Modifier.height(10.dp))
                    PersonaPicker(personas, selectedPersona, onChange = { selectedPersona = it })
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onCreateSession(selectedPersona.ifBlank { personas.firstOrNull()?.id.orEmpty() }) },
                            enabled = personas.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text("开始聊天") }
                        OutlinedButton(onClick = { showPersonaForm = !showPersonaForm }) { Text("聊天风格") }
                    }
                    if (showPersonaForm) {
                        PersonaForm(onCreatePersona)
                    }
                }
            }
            item {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("同步聊天") }
            }
            items(sessions) { session ->
                SectionCard(Modifier.clickable { onSelectSession(session) }) {
                    Text(session.title, fontWeight = FontWeight.SemiBold)
                    Text("由 ${displayName(session.createdBy)} 创建", color = Color(0xFF766A70))
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackToSessions) { Text("返回") }
            TextButton(onClick = { onSelectSession(selectedSession) }) { Text("刷新") }
            Text(selectedSession.title, fontWeight = FontWeight.Bold)
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message ->
                val mine = message.senderId == currentUserId
                val label = when {
                    message.senderId == "bot" -> "小陪伴"
                    mine -> "我"
                    else -> displayName(message.senderId)
                }
                MessageBubble(label, message.text.ifBlank { "正在想怎么回复你..." }, mine, message.senderId == "bot")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, label = { Text("想说的话") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    draft = ""
                    onSend(text)
                }
            }) { Text("发送") }
        }
    }
}

@Composable
private fun MessageBubble(label: String, text: String, mine: Boolean, bot: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    bot -> Color(0xFFF2EDFF)
                    mine -> Color(0xFFFFE2EA)
                    else -> Color.White
                }
            ),
            modifier = Modifier.fillMaxWidth(0.86f)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF7C5260))
                Text(text)
            }
        }
    }
}

@Composable
private fun WorldHomeScreen(
    distance: DistanceState?,
    notes: List<Note>,
    calendarEvents: List<CalendarEvent>,
    albumQuota: AlbumQuotaState?,
    onOpen: (WorldPage) -> Unit,
    onRefreshDistance: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(Modifier.background(softBrush())) {
                Text("两个人的小世界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(distanceText(distance), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF7C3348))
                TextButton(onClick = onRefreshDistance) { Text("更新距离") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureCard("我想对你说", "把此刻的话留下来", "♡", Modifier.weight(1f)) { onOpen(WorldPage.Notes) }
                FeatureCard("日历", "记住重要的日子", "○", Modifier.weight(1f)) { onOpen(WorldPage.Calendar) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureCard("相册", albumQuotaText(albumQuota), "□", Modifier.weight(1f)) { onOpen(WorldPage.Album) }
                FeatureCard("距离", distanceText(distance), "◇", Modifier.weight(1f), onRefreshDistance)
            }
        }
        item {
            SectionCard {
                Text("最近留下的话", fontWeight = FontWeight.SemiBold)
                Text(notes.firstOrNull()?.text ?: "还没有留言，可以先写一句想对对方说的话。", color = Color(0xFF6F5F66))
            }
        }
        item {
            SectionCard {
                Text("近期日子", fontWeight = FontWeight.SemiBold)
                Text(calendarEvents.firstOrNull()?.let { "${it.date} · ${it.title}" } ?: "还没有记录重要日子。", color = Color(0xFF6F5F66))
            }
        }
    }
}

@Composable
private fun NotesScreen(
    currentUserId: String,
    notes: List<Note>,
    onBack: () -> Unit,
    onCreate: (String) -> Unit,
    onMarkRead: (Note) -> Unit
) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageTitle("我想对你说", onBack)
        SectionCard {
            OutlinedTextField(text, { text = it }, label = { Text("写下想说的话") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val value = text.trim()
                    if (value.isNotEmpty()) {
                        text = ""
                        onCreate(value)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("留下这句话") }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(notes) { note ->
                LaunchedEffect(note.id) { onMarkRead(note) }
                val mine = note.authorId == currentUserId
                MessageBubble(if (mine) "我说的" else "${displayName(note.authorId)}说的", note.text, mine, false)
            }
        }
    }
}

@Composable
private fun CalendarScreen(
    events: List<CalendarEvent>,
    onBack: () -> Unit,
    onCreate: (String, String, String) -> Unit,
    onUpdate: (String, String, String, String) -> Unit,
    onDelete: (CalendarEvent) -> Unit
) {
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    var date by remember { mutableStateOf(todayText()) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageTitle("日历", onBack)
        SectionCard {
            Text(if (editing == null) "记下一个日子" else "修改这个日子", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(date, { date = it }, label = { Text("日期，例如 2026-05-25") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(title, { title = it }, label = { Text("这是什么日子") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        val current = editing
                        if (date.isNotBlank() && title.isNotBlank()) {
                            if (current == null) onCreate(date.trim(), title.trim(), note.trim())
                            else onUpdate(current.id, date.trim(), title.trim(), note.trim())
                            editing = null
                            date = todayText()
                            title = ""
                            note = ""
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (editing == null) "保存" else "更新") }
                if (editing != null) {
                    OutlinedButton(onClick = {
                        editing = null
                        date = todayText()
                        title = ""
                        note = ""
                    }) { Text("取消") }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { event ->
                SectionCard {
                    Text("${event.date} · ${event.title}", fontWeight = FontWeight.SemiBold)
                    if (event.note.isNotBlank()) Text(event.note, color = Color(0xFF6F5F66))
                    Text("由 ${displayName(event.createdBy)} 记录", color = Color(0xFF8A747B))
                    Row {
                        TextButton(onClick = {
                            editing = event
                            date = event.date
                            title = event.title
                            note = event.note
                        }) { Text("编辑") }
                        TextButton(onClick = { onDelete(event) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumScreen(
    items: List<AlbumItem>,
    quota: AlbumQuotaState?,
    onBack: () -> Unit,
    onUpload: (String, String, ByteArray, String) -> Unit,
    onDelete: (AlbumItem) -> Unit,
    onRename: (AlbumItem, String) -> Unit,
    onLoadItem: (String, (AlbumItem) -> Unit) -> Unit,
    onSaveImage: (AlbumItem) -> Unit
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<AlbumItem?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = context.displayName(uri)
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            onUpload(name, mimeType, bytes, base64)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageTitle("相册", onBack)
        SectionCard {
            Text("把照片和视频放在这里", fontWeight = FontWeight.SemiBold)
            Text(albumQuotaText(quota), color = Color(0xFF6F5F66))
            Spacer(Modifier.height(8.dp))
            Button(onClick = { picker.launch(arrayOf("image/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("添加照片或视频") }
        }
        preview?.let { item ->
            Spacer(Modifier.height(10.dp))
            AlbumPreview(item)
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items) { item ->
                AlbumRow(
                    item = item,
                    onPreview = { onLoadItem(item.id) { preview = it } },
                    onRename = onRename,
                    onDownload = {
                        onLoadItem(item.id) { fullItem ->
                            preview = fullItem
                            onSaveImage(fullItem)
                        }
                    },
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun AlbumPreview(item: AlbumItem) {
    SectionCard {
        Text(item.fileName, fontWeight = FontWeight.SemiBold)
        val bytes = item.dataBase64?.let { Base64.decode(it, Base64.DEFAULT) }
        if (item.mediaType == "image" && bytes != null) {
            val bitmap = remember(item.id) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "相册照片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            Spacer(Modifier.height(8.dp))
            Text("轻点列表中的照片可以在这里预览。", color = Color(0xFF8A747B), style = MaterialTheme.typography.bodySmall)
        } else {
            Text("这是一段视频，已经保存在相册里。", color = Color(0xFF6F5F66))
        }
    }
}

@Composable
private fun AlbumRow(
    item: AlbumItem,
    onPreview: () -> Unit,
    onRename: (AlbumItem, String) -> Unit,
    onDownload: () -> Unit,
    onDelete: (AlbumItem) -> Unit
) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var name by remember(item.fileName) { mutableStateOf(fileBaseName(item.fileName)) }
    SectionCard(Modifier.clickable { onPreview() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFFE8EE)),
                contentAlignment = Alignment.Center
            ) {
                Text(if (item.mediaType == "video") "视频" else "照片")
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名字") },
                        suffix = { Text(fileExtension(item.fileName)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(item.fileName, fontWeight = FontWeight.SemiBold)
                    Text("${displayName(item.uploaderId)} · ${sizeText(item.byteSize)}", color = Color(0xFF6F5F66))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (editing) {
                TextButton(onClick = {
                    val value = name.trim()
                    if (value.isNotEmpty()) {
                        onRename(item, value)
                        editing = false
                    }
                }) { Text("保存") }
                TextButton(onClick = {
                    name = fileBaseName(item.fileName)
                    editing = false
                }) { Text("取消") }
            } else {
                TextButton(onClick = { editing = true }) { Text("改名") }
                if (item.mediaType == "image") TextButton(onClick = onDownload) { Text("下载") }
                TextButton(onClick = { onDelete(item) }) { Text("删除") }
            }
        }
    }
}

@Composable
private fun MineScreen(
    userName: String,
    personas: List<Persona>,
    onCreatePersona: (String, String, String) -> Unit,
    onLogout: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Text("我的", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("当前身份：$userName", color = Color(0xFF6F5F66))
            }
        }
        item {
            SectionCard {
                Text("聊天风格", fontWeight = FontWeight.SemiBold)
                Text("可以给小陪伴增加不同的说话方式。", color = Color(0xFF6F5F66))
                PersonaForm(onCreatePersona)
            }
        }
        items(personas) { persona ->
            SectionCard {
                Text(persona.name, fontWeight = FontWeight.SemiBold)
                Text(persona.description, color = Color(0xFF6F5F66))
            }
        }
        item {
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
        }
    }
}

@Composable
private fun PersonaForm(onCreatePersona: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(desc, { desc = it }, label = { Text("说话风格") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(memory, { memory = it }, label = { Text("需要记住的事") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                onCreatePersona(name.trim(), desc.trim(), memory.trim())
                name = ""
                desc = ""
                memory = ""
            },
            enabled = name.isNotBlank() && desc.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("保存聊天风格") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaPicker(personas: List<Persona>, selected: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val name = personas.firstOrNull { it.id == selected }?.name ?: "选择聊天风格"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = name,
            onValueChange = {},
            readOnly = true,
            label = { Text("聊天风格") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            personas.forEach { persona ->
                DropdownMenuItem(
                    text = { Text(persona.name) },
                    onClick = {
                        onChange(persona.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun FeatureCard(title: String, subtitle: String, mark: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .height(118.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(mark, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color(0xFF7B626A), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun PageTitle(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("返回") }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun softBrush(): Brush = Brush.verticalGradient(
    listOf(Color(0xFFFFF1F4), Color(0xFFFFFBF8), Color(0xFFF7F2FF))
)

private enum class WorldPage {
    Notes,
    Calendar,
    Album
}

private fun tabIcon(tab: Tab): String = when (tab) {
    Tab.Chat -> "聊"
    Tab.World -> "界"
    Tab.Mine -> "我"
}

private fun displayName(userId: String): String = when (userId) {
    "hkf" -> "锋宝"
    "cl" -> "璐宝"
    "bot" -> "小陪伴"
    else -> userId
}

private fun distanceText(distance: DistanceState?): String =
    if (distance?.available == true && distance.kilometers != null) "相隔约 ${distance.kilometers} 公里" else "等两个人都打开后显示距离"

private fun albumQuotaText(quota: AlbumQuotaState?): String =
    quota?.let { "已用 ${sizeText(it.usedBytes)} / ${sizeText(it.limitBytes)}" } ?: "相册空间 200兆"

private fun sizeText(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb < 10) String.format("%.1f兆", mb) else "${mb.toInt()}兆"
}

private fun todayText(): String {
    val calendar = java.util.Calendar.getInstance()
    val year = calendar.get(java.util.Calendar.YEAR)
    val month = calendar.get(java.util.Calendar.MONTH) + 1
    val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
    return "%04d-%02d-%02d".format(year, month, day)
}

private fun Context.displayName(uri: Uri): String {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
    }
    return "珍贵回忆"
}

private fun fileBaseName(fileName: String): String =
    fileName.substringBeforeLast('.', fileName)

private fun fileExtension(fileName: String): String {
    val index = fileName.lastIndexOf('.')
    return if (index > 0 && index < fileName.lastIndex) fileName.substring(index) else ""
}

private fun saveImageToGallery(context: Context, item: AlbumItem) {
    if (item.mediaType != "image") error("只能下载图片")
    val data = item.dataBase64 ?: error("图片还没有加载完成")
    val bytes = Base64.decode(data, Base64.DEFAULT)
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, item.fileName)
        put(MediaStore.Images.Media.MIME_TYPE, item.mimeType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FL小世界")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建图片")
    resolver.openOutputStream(uri)?.use { output -> output.write(bytes) } ?: error("无法写入图片")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
