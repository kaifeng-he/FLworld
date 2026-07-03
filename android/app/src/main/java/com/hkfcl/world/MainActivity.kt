package com.hkfcl.world

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )
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
            primary = Color(0xFFE14F82),
            secondary = Color(0xFF7253B5),
            tertiary = Color(0xFFE8A049),
            background = Color(0xFFFAF3FC),
            surface = Color(0xFFFFFAFE),
            surfaceVariant = Color(0xFFF6E6F4)
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
    var unreadNotes by remember { mutableStateOf(0) }
    var calendarEvents by remember { mutableStateOf(emptyList<CalendarEvent>()) }
    var albumItems by remember { mutableStateOf(emptyList<AlbumItem>()) }
    var albumQuota by remember { mutableStateOf<AlbumQuotaState?>(null) }
    var memoryDocuments by remember { mutableStateOf(emptyList<MemoryDocument>()) }
    var aiMemories by remember { mutableStateOf(emptyList<AiMemory>()) }
    var loginStatus by remember { mutableStateOf("") }
    var loginInProgress by remember { mutableStateOf(false) }
    var errorLogs by remember { mutableStateOf(emptyList<String>()) }
    var backgroundClarity by remember { mutableStateOf(prefs.getFloat(BACKGROUND_CLARITY_KEY, DEFAULT_BACKGROUND_CLARITY)) }
    var readingOverlayStrength by remember {
        mutableStateOf(prefs.getFloat(READING_OVERLAY_STRENGTH_KEY, DEFAULT_READING_OVERLAY_STRENGTH))
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val deviceId = remember {
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    fun clearLogin(message: String? = null) {
        prefs.edit().remove("token").apply()
        token = null
        selectedSession = null
        messages = emptyList()
        message?.let {
            errorLogs = listOf("${timeText(nowIsoText())}  $it") + errorLogs
            scope.launch { snackbarHostState.showSnackbar(it) }
        }
    }

    val api = remember(token) { ApiClient(token) { scope.launch { clearLogin("该身份已在另一台设备登录，请重新进入小世界") } } }

    fun report(message: String) {
        errorLogs = listOf("${timeText(nowIsoText())}  $message") + errorLogs
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    fun refreshAll() {
        scope.launch {
            runCatching {
                personas = api.personas()
                sessions = api.sessions()
                distance = api.distance()
                val noteState = api.notes()
                notes = noteState.notes
                unreadNotes = noteState.unreadCount
                calendarEvents = sortedCalendarEvents(api.calendarEvents())
                memoryDocuments = api.memoryDocuments()
                aiMemories = api.aiMemories()
                val album = api.album()
                albumItems = album.first
                albumQuota = album.second
            }.onFailure { report(it.message ?: "同步失败") }
        }
    }

    fun refreshAlbum() {
        scope.launch {
            runCatching { api.album() }
                .onSuccess {
                    albumItems = it.first
                    albumQuota = it.second
                }
                .onFailure { report(it.message ?: "相册同步失败") }
        }
    }

    fun syncLocation() {
        scope.launch {
            if (!locationHelper.hasLocationServiceEnabled()) {
                report("请先打开手机系统定位服务，再更新距离")
                return@launch
            }
            val location = locationHelper.currentCoarseLocation()
            if (location == null) {
                report("暂时没拿到当前位置，请确认定位已开启后再点更新距离")
                return@launch
            }
            runCatching {
                api.updateLocation(location.latitude, location.longitude, location.province, location.city)
                distance = api.distance()
            }.onFailure { report(it.message ?: "距离更新失败") }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) syncLocation() else report("没有定位权限，暂时不能显示距离")
    }

    LaunchedEffect(token) {
        if (token != null) {
            refreshAll()
            if (locationHelper.hasPermission()) syncLocation() else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    LaunchedEffect(token) {
        while (token != null) {
            delay(15_000)
            refreshAll()
        }
    }

    if (token == null) {
        LoginScreen(
            backgroundClarity = backgroundClarity,
            readingOverlayStrength = readingOverlayStrength,
            selectedUserId = userId,
            code = code,
            status = loginStatus,
            isLoading = loginInProgress,
            onUserId = { userId = it },
            onCode = { code = it },
            onLogin = {
                if (!loginInProgress) {
                    loginInProgress = true
                    scope.launch {
                        try {
                            runCatching { api.login(userId, code.ifBlank { userId }, deviceId) }
                                .onSuccess { result ->
                                    token = result.first
                                    userId = result.second.id
                                    userName = result.second.name
                                    prefs.edit()
                                        .putString("token", result.first)
                                        .putString("userId", result.second.id)
                                        .putString("userName", result.second.name)
                                        .apply()
                                    loginStatus = ""
                                }
                                .onFailure { loginStatus = it.message ?: "登录失败" }
                        } finally {
                            loginInProgress = false
                        }
                    }
                }
            }
        )
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = NAVIGATION_BAR_COLOR) {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            tab = item
                            activeWorldPage = null
                        },
                        label = { Text(item.label) },
                        icon = { Text(tabIcon(item)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF553561),
                            selectedTextColor = Color(0xFF553561),
                            indicatorColor = Color(0xFFEEDCFF),
                            unselectedIconColor = Color(0xFF695B67),
                            unselectedTextColor = Color(0xFF695B67)
                        )
                    )
                }
            }
        }
    ) { padding ->
        AppBackground(backgroundClarity, readingOverlayStrength) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
            ) {
                Crossfade(targetState = tab, animationSpec = tween(230), label = "main-tab-transition") { selectedTab ->
                when (selectedTab) {
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
                                .onFailure { report(it.message ?: "加载聊天失败") }
                        }
                    },
                    onBackToSessions = {
                        selectedSession = null
                        messages = emptyList()
                    },
                    onCreatePersona = { name, desc, memory, bubbleColor ->
                        scope.launch {
                            runCatching { api.createPersona(name, desc, memory, bubbleColor) }
                                .onSuccess { personas = personas + it }
                                .onFailure { report(it.message ?: "保存聊天风格失败") }
                        }
                    },
                    onUpdatePersona = { persona, name, desc, memory, bubbleColor ->
                        scope.launch {
                            runCatching { api.updatePersona(persona.id, name, desc, memory, bubbleColor) }
                                .onSuccess { updated ->
                                    personas = personas.map { if (it.id == updated.id) updated else it }
                                }
                                .onFailure { report(it.message ?: "更新聊天风格失败") }
                        }
                    },
                    onDeletePersona = { persona ->
                        scope.launch {
                            runCatching { api.deletePersona(persona.id) }
                                .onSuccess {
                                    personas = personas.filterNot { it.id == persona.id }
                                    sessions = api.sessions()
                                }
                                .onFailure { report(it.message ?: "删除聊天风格失败") }
                        }
                    },
                    onCreateSession = { personaId ->
                        scope.launch {
                            runCatching { api.createSession("新的聊天", personaId.ifBlank { DEFAULT_PERSONA_ID }) }
                                .onSuccess {
                                    selectedSession = it
                                    sessions = listOf(it) + sessions
                                    messages = emptyList()
                                }
                                .onFailure { report(it.message ?: "新建聊天失败") }
                        }
                    },
                    onDeleteSession = { session ->
                        scope.launch {
                            runCatching { api.deleteSession(session.id) }
                                .onSuccess {
                                    sessions = sessions.filterNot { it.id == session.id }
                                    if (selectedSession?.id == session.id) {
                                        selectedSession = null
                                        messages = emptyList()
                                    }
                                }
                                .onFailure { report(it.message ?: "删除聊天失败") }
                        }
                    },
                    onSend = { text ->
                        val session = selectedSession ?: return@ChatScreen
                        val pendingBotId = "local-bot-${System.currentTimeMillis()}"
                        val localTime = nowIsoText()
                        messages = messages + ChatMessage("local-user-$localTime", session.id, "user", userId, text, localTime) +
                            ChatMessage(pendingBotId, session.id, "assistant", "bot", "", localTime)
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
                            }.onFailure { report(it.message ?: "发送失败") }
                        }
                    }
                )

                    Tab.World -> Crossfade(targetState = activeWorldPage, animationSpec = tween(230), label = "world-page-transition") { page ->
                    when (page) {
                    null -> WorldHomeScreen(
                        distance = distance,
                        unreadNotes = unreadNotes,
                        calendarEvents = calendarEvents,
                        albumQuota = albumQuota,
                        onOpen = { activeWorldPage = it },
                        onRefreshDistance = {
                            if (locationHelper.hasPermission()) syncLocation()
                            else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
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
                                        notes = (notes + it).sortedBy { item -> item.createdAt }
                                    }
                                    .onFailure { report(it.message ?: "留言失败") }
                            }
                        },
                        onMarkRead = { note ->
                            if (note.authorId != userId && note.readAt == null) {
                                scope.launch {
                                    runCatching { api.markNoteRead(note.id) }
                                        .onSuccess {
                                            notes = notes.map { if (it.id == note.id) it.copy(readAt = nowIsoText()) else it }
                                            unreadNotes = (unreadNotes - 1).coerceAtLeast(0)
                                        }
                                }
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
                                        calendarEvents = sortedCalendarEvents(calendarEvents + it)
                                    }
                                    .onFailure { report(it.message ?: "保存日历失败") }
                            }
                        },
                        onUpdate = { event, date, title, note ->
                            scope.launch {
                                runCatching { api.updateCalendarEvent(event.id, date, title, note, event.revision) }
                                    .onSuccess { updated ->
                                        calendarEvents = sortedCalendarEvents(calendarEvents.map { if (it.id == updated.id) updated else it })
                                    }
                                    .onFailure { report(it.message ?: "更新日历失败") }
                            }
                        },
                        onDelete = { event ->
                            scope.launch {
                                runCatching { api.deleteCalendarEvent(event) }
                                    .onSuccess {
                                        calendarEvents = calendarEvents.filterNot { it.id == event.id }
                                    }
                                    .onFailure { report(it.message ?: "删除日历失败") }
                            }
                        }
                    )
                    WorldPage.Album -> AlbumScreen(
                        items = albumItems,
                        quota = albumQuota,
                        onBack = { activeWorldPage = null },
                        onUpload = { name, mimeType, bytes, base64, previewBase64 ->
                            scope.launch {
                                runCatching { api.uploadAlbumItem(name, mimeType, bytes, base64, previewBase64) }
                                    .onSuccess { refreshAlbum() }
                                    .onFailure { report(it.message ?: "上传失败") }
                            }
                        },
                        onDelete = { item ->
                            scope.launch {
                                runCatching { api.deleteAlbumItem(item) }
                                    .onSuccess { refreshAlbum() }
                                    .onFailure { report(it.message ?: "删除失败") }
                            }
                        },
                        onRename = { item, name ->
                            scope.launch {
                                runCatching { api.renameAlbumItem(item.id, name, item.revision) }
                                    .onSuccess { renamed ->
                                        albumItems = albumItems.map { if (it.id == renamed.id) renamed else it }
                                    }
                                    .onFailure { report(it.message ?: "改名失败") }
                            }
                        },
                        onLoadPreview = { id -> api.albumPreview(id) },
                        onBackfillPreview = { id, base64 -> api.saveAlbumPreview(id, base64) },
                        onLoadItem = { id -> api.albumItem(id) },
                        onError = { report(it) },
                        onSaveImage = { item ->
                            saveImageToGallery(context, item)
                        },
                        onNotify = { notify(it) }
                    )
                    WorldPage.Memory -> MemoryScreen(
                        documents = memoryDocuments,
                        memories = aiMemories,
                        onBack = { activeWorldPage = null },
                        onSaveDocument = { existing, title, content ->
                            scope.launch {
                                runCatching {
                                    if (existing == null) api.createMemoryDocument(title, content)
                                    else api.updateMemoryDocument(existing, title, content)
                                }.onSuccess { refreshAll() }
                                    .onFailure { report(it.message ?: "保存回忆失败"); refreshAll() }
                            }
                        },
                        onDeleteDocument = { document ->
                            scope.launch {
                                runCatching { api.deleteMemoryDocument(document) }
                                    .onSuccess { refreshAll() }
                                    .onFailure { report(it.message ?: "删除回忆失败") }
                            }
                        },
                        onSaveAiMemory = { memory, content ->
                            scope.launch {
                                runCatching { api.updateAiMemory(memory, content) }
                                    .onSuccess { refreshAll() }
                                    .onFailure { report(it.message ?: "修改小暖记忆失败"); refreshAll() }
                            }
                        },
                        onDeleteAiMemory = { memory ->
                            scope.launch {
                                runCatching { api.deleteAiMemory(memory) }
                                    .onSuccess { refreshAll() }
                                    .onFailure { report(it.message ?: "删除小暖记忆失败") }
                            }
                        },
                        onGenerate = { done ->
                            scope.launch {
                                runCatching { api.refreshAiMemories() }
                                    .onSuccess { count ->
                                        refreshAll()
                                        notify(if (count > 0) "小暖新记住了 $count 件事" else "小暖暂时没有发现新的长期回忆")
                                    }
                                    .onFailure { report(it.message ?: "整理故事失败") }
                                done()
                            }
                        }
                    )
                    }
                }

                    Tab.Mine -> MineScreen(
                    userName = userName,
                    errorLogs = errorLogs,
                    backgroundClarity = backgroundClarity,
                    onBackgroundClarityChanged = { clarity ->
                        backgroundClarity = clarity
                        prefs.edit().putFloat(BACKGROUND_CLARITY_KEY, clarity).apply()
                    },
                    readingOverlayStrength = readingOverlayStrength,
                    onReadingOverlayStrengthChanged = { strength ->
                        readingOverlayStrength = strength
                        prefs.edit().putFloat(READING_OVERLAY_STRENGTH_KEY, strength).apply()
                    },
                    onLogout = {
                        prefs.edit()
                            .remove("token")
                            .remove("userId")
                            .remove("userName")
                            .apply()
                        token = null
                        selectedSession = null
                        messages = emptyList()
                    }
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    backgroundClarity: Float,
    readingOverlayStrength: Float,
    selectedUserId: String,
    code: String,
    status: String,
    isLoading: Boolean,
    onUserId: (String) -> Unit,
    onCode: (String) -> Unit,
    onLogin: () -> Unit
) {
    AppBackground(backgroundClarity, readingOverlayStrength) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFFFFFBFD).copy(alpha = 0.94f))
                    .border(BorderStroke(1.dp, Color(0xFFFFFFFF).copy(alpha = 0.86f)), RoundedCornerShape(28.dp))
                    .padding(22.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "FL小世界",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF5E2440)
                )
                Text(
                    "只属于两个人的陪伴、记录和日常。",
                    color = Color(0xFF57424A),
                    modifier = Modifier.padding(top = 6.dp)
                )
                Spacer(Modifier.height(28.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    IdentityButton(
                        "锋宝",
                        selectedUserId == "hkf",
                        Modifier.weight(1f),
                        enabled = !isLoading
                    ) { onUserId("hkf") }
                    IdentityButton(
                        "璐宝",
                        selectedUserId == "cl",
                        Modifier.weight(1f),
                        enabled = !isLoading
                    ) { onUserId("cl") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = onCode,
                    label = { Text("进入口令") },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !isLoading,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color(0xFF34242C),
                        unfocusedTextColor = Color(0xFF34242C),
                        focusedLabelColor = Color(0xFF5E2440),
                        unfocusedLabelColor = Color(0xFF6D5961),
                        focusedBorderColor = Color(0xFF5E2440),
                        unfocusedBorderColor = Color(0xFF8E717D),
                        cursorColor = Color(0xFF5E2440),
                        focusedContainerColor = Color.White.copy(alpha = 0.72f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.72f),
                        disabledContainerColor = Color.White.copy(alpha = 0.52f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onLogin,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5E2440),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF8D7880),
                        disabledContentColor = Color.White
                    )
                ) {
                    Text(if (isLoading) "正在进入..." else "进入小世界", fontWeight = FontWeight.SemiBold)
                }
                if (status.isNotBlank()) {
                    Text(status, color = Color(0xFF8A1E34), modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun IdentityButton(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentColor = if (selected) Color.White else Color(0xFF4E3944)
    val containerColor = if (selected) Color(0xFF6A2A48) else Color.White.copy(alpha = 0.72f)
    val borderColor = if (selected) Color(0xFF6A2A48) else Color(0xFF8E717D)
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = Color(0xFFE9DDE2),
            disabledContentColor = Color(0xFF806A73)
        ),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Text(text, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
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
    onCreatePersona: (String, String, String, String) -> Unit,
    onUpdatePersona: (Persona, String, String, String, String) -> Unit,
    onDeletePersona: (Persona) -> Unit,
    onCreateSession: (String) -> Unit,
    onDeleteSession: (ChatSession) -> Unit,
    onSend: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var showPersonaManager by remember { mutableStateOf(false) }
    var editingPersona by remember { mutableStateOf<Persona?>(null) }
    var deletingPersona by remember { mutableStateOf<Persona?>(null) }
    var deletingSession by remember { mutableStateOf<ChatSession?>(null) }
    var selectedPersonaId by remember { mutableStateOf(DEFAULT_PERSONA_ID) }
    val messageListState = rememberLazyListState()
    val activePersona = selectedSession?.let { session -> personas.firstOrNull { it.id == session.personaId } }
    val defaultPersonaName = personas.firstOrNull { it.id == DEFAULT_PERSONA_ID }?.name ?: "温柔情感陪伴"
    val selectedPersonaName = personas.firstOrNull { it.id == selectedPersonaId }?.name ?: defaultPersonaName
    fun personaNameFor(personaId: String): String = personas.firstOrNull { it.id == personaId }?.name ?: defaultPersonaName

    LaunchedEffect(personas, selectedPersonaId) {
        if (personas.none { it.id == selectedPersonaId }) selectedPersonaId = DEFAULT_PERSONA_ID
    }

    LaunchedEffect(selectedSession?.id, messages.size, messages.lastOrNull()?.text) {
        if (selectedSession != null && messages.isNotEmpty()) {
            messageListState.scrollToItem(messages.lastIndex)
        }
    }

    if (selectedSession == null) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard {
                    Text("聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF6B2944))
                    Text("新建对话风格：$selectedPersonaName", color = Color(0xFF66546F))
                    Text("创建后这个会话会固定使用该风格，双方打开时一致。", color = Color(0xFF6F5F66), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    PersonaPicker(personas, selectedPersonaId) { selectedPersonaId = it }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onCreateSession(selectedPersonaId) },
                            enabled = personas.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) { Text("新建对话") }
                        OutlinedButton(onClick = { showPersonaManager = true }) { Text("管理风格") }
                    }
                }
            }
            item {
                Text("历史对话", fontWeight = FontWeight.SemiBold, color = Color(0xFF6B2944), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            }
            if (sessions.isEmpty()) {
                item {
                    SectionCard {
                        Text("还没有历史对话。", color = Color(0xFF6F5F66))
                    }
                }
            }
            items(sessions) { session ->
                SectionCard(Modifier.clickable { onSelectSession(session) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${dateText(session.updatedAt)} · 由 ${displayName(session.createdBy)} 创建 · 风格：${personaNameFor(session.personaId)}",
                                color = Color(0xFF766A70)
                            )
                        }
                        TextButton(onClick = { deletingSession = session }) { Text("删除") }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("同步聊天") }
            }
        }
        if (showPersonaManager) {
            PersonaManagerDialog(
                personas = personas,
                editingPersona = editingPersona,
                onEdit = { persona -> editingPersona = persona },
                onDelete = { persona -> deletingPersona = persona },
                onSave = { persona, name, desc, memory, color ->
                    if (persona == null) onCreatePersona(name, desc, memory, color)
                    else onUpdatePersona(persona, name, desc, memory, color)
                    editingPersona = null
                    showPersonaManager = false
                },
                onCancelEdit = { editingPersona = null },
                onDismiss = {
                    editingPersona = null
                    showPersonaManager = false
                }
            )
        }
        deletingPersona?.let { persona ->
            ConfirmDialog(
                title = "删除聊天风格",
                text = "删除“${persona.name}”后，使用它的历史会话会改回默认风格。",
                onDismiss = { deletingPersona = null },
                onConfirm = {
                    deletingPersona = null
                    onDeletePersona(persona)
                }
            )
        }
        deletingSession?.let { session ->
            ConfirmDialog(
                title = "删除历史会话",
                text = "会话“${session.title}”和里面的聊天记录都会删除。",
                onDismiss = { deletingSession = null },
                onConfirm = {
                    deletingSession = null
                    onDeleteSession(session)
                }
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBackToSessions) { Text("返回") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        selectedSession.title,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text("当前聊天风格：${activePersona?.name ?: defaultPersonaName}", color = Color(0xFF766A70), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onSelectSession(selectedSession) }) { Text("刷新") }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(
            state = messageListState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                val mine = message.senderId == currentUserId
                val label = when {
                    message.senderId == "bot" -> "小暖"
                    mine -> "我"
                    else -> displayName(message.senderId)
                }
                MessageBubble(
                    label = label,
                    text = message.text.ifBlank { "正在想怎么回复你..." },
                    time = timeText(message.createdAt),
                    alignEnd = mine,
                    color = messageColor(message.senderId, activePersona?.bubbleColor)
                )
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
private fun MessageBubble(label: String, text: String, time: String, alignEnd: Boolean, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = color),
            modifier = Modifier.fillMaxWidth(0.86f)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF7C5260))
                Text(text)
                if (time.isNotBlank()) {
                    Text(time, style = MaterialTheme.typography.labelSmall, color = Color(0xFF7B626A), modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun WorldHomeScreen(
    distance: DistanceState?,
    unreadNotes: Int,
    calendarEvents: List<CalendarEvent>,
    albumQuota: AlbumQuotaState?,
    onOpen: (WorldPage) -> Unit,
    onRefreshDistance: () -> Unit
) {
    var showAllDays by remember { mutableStateOf(false) }
    var showDistance by remember { mutableStateOf(false) }
    val displayEvents = sortedCalendarEvents(calendarEvents)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            HighlightSectionCard {
                Text("两个人的小世界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    distanceText(distance),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF7C3348),
                    modifier = Modifier.clickable { showDistance = true }
                )
                Text("点击查看我们的大致位置", color = Color(0xFF85697E), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRefreshDistance) { Text("更新距离") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureCard("我想对你说", "把此刻的话留下来", "💌", Modifier.weight(1f), unreadNotes) { onOpen(WorldPage.Notes) }
                FeatureCard("日历", "记住重要的日子", "📅", Modifier.weight(1f)) { onOpen(WorldPage.Calendar) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FeatureCard("相册", albumQuotaText(albumQuota), "🖼️", Modifier.weight(1f)) { onOpen(WorldPage.Album) }
                FeatureCard("回忆", "和小暖一起珍藏故事", "📖", Modifier.weight(1f)) { onOpen(WorldPage.Memory) }
            }
        }
        item {
            SectionCard(Modifier.clickable { showAllDays = true }) {
                Text("近期日子", fontWeight = FontWeight.SemiBold)
                if (displayEvents.isEmpty()) {
                    Text("还没有记录重要日子。", color = Color(0xFF6F5F66))
                } else {
                    displayEvents.take(2).forEach { event ->
                        CompactCalendarCard(event)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (displayEvents.size > 2) {
                        Text("还有更多日子...", color = Color(0xFF8A747B), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("点击查看所有日子", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    if (showAllDays) {
        CalendarEventsDialog(events = displayEvents, onDismiss = { showAllDays = false })
    }
    if (showDistance) {
        DistanceDialog(distance = distance, onDismiss = { showDistance = false })
    }
}

@Composable
private fun CompactCalendarCard(event: CalendarEvent) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3F8)),
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text("${event.date} · ${event.title}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(calendarDistanceText(event.date), event.note.takeIf { it.isNotBlank() }).joinToString(" · "),
                color = Color(0xFF766A70),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DistanceDialog(distance: DistanceState?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我们的大致位置", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(distanceText(distance), color = Color(0xFF7C3348), fontWeight = FontWeight.SemiBold)
                listOf(distance?.mine, distance?.other).forEach { location ->
                    if (location != null) {
                        SectionCard {
                            Text(location.name, fontWeight = FontWeight.SemiBold)
                            Text(locationText(location), color = Color(0xFF6F5F66))
                            Text("更新于 ${timeText(location.updatedAt)}", color = Color(0xFF8A747B), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (distance?.available != true) Text("等待两个人都打开 App 更新位置。", color = Color(0xFF6F5F66))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
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
    val sortedNotes = remember(notes) { notes.sortedBy { it.createdAt } }
    val unreadNoteIds = sortedNotes
        .filter { it.authorId != currentUserId && it.readAt == null }
        .joinToString("|") { it.id }
    val noteListState = rememberLazyListState()
    LaunchedEffect(unreadNoteIds) {
        sortedNotes
            .filter { it.authorId != currentUserId && it.readAt == null }
            .forEach { onMarkRead(it) }
    }
    LaunchedEffect(sortedNotes.size, sortedNotes.lastOrNull()?.id) {
        if (sortedNotes.isNotEmpty()) noteListState.scrollToItem(sortedNotes.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle("我想对你说", onBack, "给对方留下一句只属于你们的话")
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
        LazyColumn(
            state = noteListState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sortedNotes) { note ->
                val mine = note.authorId == currentUserId
                MessageBubble(
                    label = if (mine) "我说的" else "${displayName(note.authorId)}说的",
                    text = note.text,
                    time = timeText(note.createdAt),
                    alignEnd = mine,
                    color = messageColor(note.authorId, null)
                )
            }
        }
    }
}

@Composable
private fun CalendarScreen(
    events: List<CalendarEvent>,
    onBack: () -> Unit,
    onCreate: (String, String, String) -> Unit,
    onUpdate: (CalendarEvent, String, String, String) -> Unit,
    onDelete: (CalendarEvent) -> Unit
) {
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    var date by remember { mutableStateOf(todayText()) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        PageTitle("日历", onBack, "把值得记住的日子写下来")
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
                            else onUpdate(current, date.trim(), title.trim(), note.trim())
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
            items(sortedCalendarEvents(events)) { event ->
                SectionCard {
                    Text("${event.date} · ${event.title}", fontWeight = FontWeight.SemiBold)
                    calendarDistanceText(event.date)?.let {
                        Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                    }
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
private fun CalendarEventsDialog(events: List<CalendarEvent>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("所有日子", fontWeight = FontWeight.Bold) },
        text = {
            if (events.isEmpty()) {
                Text("还没有记录重要日子。", color = Color(0xFF6F5F66))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sortedCalendarEvents(events)) { event ->
                        SectionCard {
                            Text("${event.date} · ${event.title}", fontWeight = FontWeight.SemiBold)
                            calendarDistanceText(event.date)?.let {
                                Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                            }
                            if (event.note.isNotBlank()) Text(event.note, color = Color(0xFF6F5F66))
                            Text("由 ${displayName(event.createdBy)} 记录", color = Color(0xFF8A747B), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun MemoryScreen(
    documents: List<MemoryDocument>,
    memories: List<AiMemory>,
    onBack: () -> Unit,
    onSaveDocument: (MemoryDocument?, String, String) -> Unit,
    onDeleteDocument: (MemoryDocument) -> Unit,
    onSaveAiMemory: (AiMemory, String) -> Unit,
    onDeleteAiMemory: (AiMemory) -> Unit,
    onGenerate: (() -> Unit) -> Unit
) {
    var editingDocument by remember { mutableStateOf<MemoryDocument?>(null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }

    fun edit(document: MemoryDocument?) {
        editingDocument = document
        title = document?.title.orEmpty()
        content = document?.content.orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        PageTitle("回忆", onBack, "我们写下的故事，也让小暖慢慢记住")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                SectionCard {
                    Text(if (editingDocument == null) "写一篇回忆" else "编辑回忆", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(title, { title = it }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(content, { content = it }, label = { Text("写下我们的故事") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                if (title.isNotBlank() && content.isNotBlank()) {
                                    onSaveDocument(editingDocument, title.trim(), content.trim())
                                    edit(null)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("保存") }
                        if (editingDocument != null) {
                            OutlinedButton(onClick = { edit(null) }) { Text("取消") }
                        }
                    }
                }
            }
            item { Text("我们写下的回忆", fontWeight = FontWeight.SemiBold, color = Color(0xFF6B2944)) }
            if (documents.isEmpty()) {
                item { SectionCard { Text("还没有手写回忆，先写下第一篇吧。", color = Color(0xFF6F5F66)) } }
            }
            items(documents, key = { it.id }) { document ->
                SectionCard {
                    Text(document.title, fontWeight = FontWeight.SemiBold)
                    Text(document.content, color = Color(0xFF6F5F66), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text("由 ${displayName(document.createdBy)} 更新", color = Color(0xFF8A747B), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { edit(document) }) { Text("编辑") }
                        TextButton(onClick = { onDeleteDocument(document) }) { Text("删除") }
                    }
                }
            }
            item {
                SectionCard {
                    Text("小暖记住的事", fontWeight = FontWeight.SemiBold)
                    Text("小暖只保存适合长期陪伴的内容，你们可以随时修改或删除。", color = Color(0xFF6F5F66), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            generating = true
                            onGenerate { generating = false }
                        },
                        enabled = !generating,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (generating) "小暖正在翻阅..." else "让小暖翻翻我们的故事") }
                }
            }
            item { Text("聊天中记住的", fontWeight = FontWeight.SemiBold, color = Color(0xFF6B2944)) }
            val chatMemories = memories.filter { it.kind == "chat" }
            if (chatMemories.isEmpty()) item { Text("还没有从聊天中留下的长期记忆。", color = Color(0xFF6F5F66)) }
            items(chatMemories, key = { it.id }) { memory ->
                AiMemoryCard(memory, onSaveAiMemory, onDeleteAiMemory)
            }
            item { Text("故事里读到的", fontWeight = FontWeight.SemiBold, color = Color(0xFF6B2944)) }
            val materialMemories = memories.filter { it.kind == "life-material" }
            if (materialMemories.isEmpty()) item { Text("点击按钮后，小暖会阅读文档、日历和留言。", color = Color(0xFF6F5F66)) }
            items(materialMemories, key = { it.id }) { memory ->
                AiMemoryCard(memory, onSaveAiMemory, onDeleteAiMemory)
            }
        }
    }
}

@Composable
private fun AiMemoryCard(memory: AiMemory, onSave: (AiMemory, String) -> Unit, onDelete: (AiMemory) -> Unit) {
    var editing by remember(memory.id) { mutableStateOf(false) }
    var text by remember(memory.content) { mutableStateOf(memory.content) }
    SectionCard {
        if (editing) {
            OutlinedTextField(text, { text = it }, label = { Text("小暖记住的内容") }, modifier = Modifier.fillMaxWidth())
        } else {
            Text(memory.content, color = Color(0xFF55404D))
            Text(memory.sourceLabel.ifBlank { memorySourceText(memory.sourceType) }, color = Color(0xFF8A747B), style = MaterialTheme.typography.bodySmall)
        }
        Row {
            if (editing) {
                TextButton(onClick = {
                    if (text.isNotBlank()) {
                        onSave(memory, text.trim())
                        editing = false
                    }
                }) { Text("保存") }
                TextButton(onClick = { text = memory.content; editing = false }) { Text("取消") }
            } else {
                TextButton(onClick = { editing = true }) { Text("编辑") }
                TextButton(onClick = { onDelete(memory) }) { Text("删除") }
            }
        }
    }
}

@Composable
private fun MineScreen(
    userName: String,
    errorLogs: List<String>,
    backgroundClarity: Float,
    onBackgroundClarityChanged: (Float) -> Unit,
    readingOverlayStrength: Float,
    onReadingOverlayStrengthChanged: (Float) -> Unit,
    onLogout: () -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard {
                Text("FL小世界", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("只属于两个人的陪伴、记录和日常。", color = Color(0xFF6F5F66))
                Spacer(Modifier.height(8.dp))
                Text("当前身份：$userName", color = Color(0xFF6F5F66))
            }
        }
        item {
            SectionCard {
                Text("显示设置", fontWeight = FontWeight.SemiBold)
                Text("背景清晰度：${(backgroundClarity * 100).toInt()}%", color = Color(0xFF6F5F66))
                Slider(
                    value = backgroundClarity,
                    onValueChange = onBackgroundClarityChanged,
                    valueRange = 0f..1f,
                    steps = 4
                )
                Text("阅读遮罩：${(readingOverlayStrength * 100).toInt()}%", color = Color(0xFF6F5F66))
                Slider(
                    value = readingOverlayStrength,
                    onValueChange = onReadingOverlayStrengthChanged,
                    valueRange = 0f..1f,
                    steps = 4
                )
                Text("清晰度调整背景虚化；阅读遮罩可独立调整或完全关闭。", color = Color(0xFF766A70), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SectionCard {
                Text("错误日志", fontWeight = FontWeight.SemiBold)
                if (errorLogs.isEmpty()) {
                    Text("这次打开 App 还没有记录到错误。", color = Color(0xFF6F5F66))
                } else {
                    errorLogs.take(20).forEach { log ->
                        Text(log, color = Color(0xFF6F5F66), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF513871)),
                border = BorderStroke(1.dp, Color(0xFF513871).copy(alpha = 0.72f))
            ) { Text("退出登录", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun PersonaManagerDialog(
    personas: List<Persona>,
    editingPersona: Persona?,
    onEdit: (Persona) -> Unit,
    onDelete: (Persona) -> Unit,
    onSave: (Persona?, String, String, String, String) -> Unit,
    onCancelEdit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("聊天风格") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "每个会话只使用一种风格，由创建会话时的选择决定；双方进入同一会话会看到并使用同一风格。编辑已有风格会影响使用它的会话后续回复。",
                        color = Color(0xFF6F5F66),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    PersonaForm(
                        persona = editingPersona,
                        onSave = onSave,
                        onCancel = onCancelEdit
                    )
                }
                items(personas) { persona ->
                    val isDefault = persona.id == DEFAULT_PERSONA_ID
                    SectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(parseColor(persona.bubbleColor))
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (isDefault) "${persona.name} · 默认" else persona.name, fontWeight = FontWeight.SemiBold)
                                Text(persona.description, color = Color(0xFF6F5F66), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { onEdit(persona) }, enabled = !isDefault) { Text("编辑") }
                            TextButton(onClick = { onDelete(persona) }, enabled = !isDefault) { Text("删除") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )
}

@Composable
private fun PersonaForm(
    persona: Persona?,
    onSave: (Persona?, String, String, String, String) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember(persona?.id) { mutableStateOf(persona?.name.orEmpty()) }
    var desc by remember(persona?.id) { mutableStateOf(persona?.description.orEmpty()) }
    var bubbleColor by remember(persona?.id) { mutableStateOf(persona?.bubbleColor ?: "#FFE0A8") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        Text(if (persona == null) "新建聊天风格" else "编辑聊天风格", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(desc, { desc = it }, label = { Text("风格指令") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Text("这里控制小暖在该会话中的表达方式；长期记忆请在小世界的“回忆”中共同管理。", color = Color(0xFF6F5F66), style = MaterialTheme.typography.bodySmall)
        Text("机器人消息颜色", color = Color(0xFF6F5F66), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            personaColorOptions().forEach { color ->
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(parseColor(color))
                        .border(
                            width = if (bubbleColor.equals(color, ignoreCase = true)) 2.dp else 1.dp,
                            color = if (bubbleColor.equals(color, ignoreCase = true)) MaterialTheme.colorScheme.primary else Color(0xFFE0D4D8),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { bubbleColor = color }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onSave(persona, name.trim(), desc.trim(), persona?.memory.orEmpty(), bubbleColor) },
                enabled = name.isNotBlank() && desc.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("保存") }
            OutlinedButton(onClick = onCancel) { Text("取消") }
        }
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
            label = { Text("新对话风格") },
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
private fun FeatureCard(title: String, subtitle: String, mark: String, modifier: Modifier = Modifier, badgeCount: Int = 0, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .height(118.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = FEATURE_CARD_COLOR),
        border = CARD_BORDER,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(mark, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                if (badgeCount > 0) {
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFFE74458)).padding(horizontal = 7.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (badgeCount > 99) "99+" else badgeCount.toString(), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color(0xFF7B626A), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = GLASS_CARD_COLOR,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = CARD_BORDER,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun HighlightSectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(softBrush())
            .border(CARD_BORDER, RoundedCornerShape(20.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
private fun AppBackground(clarity: Float, readingOverlayStrength: Float, content: @Composable () -> Unit) {
    val adjustedClarity = clarity.coerceIn(0f, 1f)
    val adjustedOverlay = readingOverlayStrength.coerceIn(0f, 1f)
    val blurRadius = (26f * (1f - adjustedClarity)).dp
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Image(
            painter = painterResource(id = R.drawable.background),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .blur(blurRadius),
            contentScale = ContentScale.Crop
        )
        if (adjustedOverlay > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF100C22).copy(alpha = adjustedOverlay * 0.54f),
                                Color(0xFFF8EAF4).copy(alpha = adjustedOverlay * 0.48f),
                                Color(0xFFFFDFEC).copy(alpha = adjustedOverlay * 0.6f)
                            )
                        )
                    )
            )
        }
        content()
    }
}

@Composable
internal fun PageTitle(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .size(44.dp)
                .clickable(onClick = onBack),
            shape = RoundedCornerShape(15.dp),
            colors = CardDefaults.cardColors(containerColor = GLASS_CARD_COLOR),
            border = CARD_BORDER,
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("‹", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFF49295D))
            subtitle?.let { Text(it, color = Color(0xFF85697E), style = MaterialTheme.typography.bodySmall) }
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun ConfirmDialog(title: String, text: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun softBrush(): Brush = Brush.verticalGradient(
    listOf(
        Color(0xFFFFE7F2),
        Color(0xFFFFF6FA),
        Color(0xFFF0E9FF)
    )
)

private enum class WorldPage {
    Notes,
    Calendar,
    Album,
    Memory
}

private const val DEFAULT_PERSONA_ID = "emotional-support"
private const val BACKGROUND_CLARITY_KEY = "backgroundClarity"
private const val DEFAULT_BACKGROUND_CLARITY = 0.45f
private const val READING_OVERLAY_STRENGTH_KEY = "readingOverlayStrength"
private const val DEFAULT_READING_OVERLAY_STRENGTH = 0.55f
private val GLASS_CARD_COLOR = Color(0xFFFFFAFD)
private val FEATURE_CARD_COLOR = Color(0xFFFFF7FB)
private val CARD_BORDER = BorderStroke(1.dp, Color(0xFFEADCE6))
private val NAVIGATION_BAR_COLOR = Color(0xFFFFF7FB).copy(alpha = 0.96f)

private fun tabIcon(tab: Tab): String = when (tab) {
    Tab.Chat -> "聊"
    Tab.World -> "界"
    Tab.Mine -> "我"
}

internal fun displayName(userId: String): String = when (userId) {
    "hkf" -> "锋宝"
    "cl" -> "璐宝"
    "bot" -> "小暖"
    else -> userId
}

private fun messageColor(senderId: String, botColor: String?): Color = when (senderId) {
    "hkf" -> Color(0xFFDCEBFF)
    "cl" -> Color(0xFFFFE2EA)
    "bot" -> parseColor(botColor ?: "#FFE0A8")
    else -> Color.White
}

private fun parseColor(value: String): Color {
    val text = value.trim()
    if (!Regex("^#[0-9A-Fa-f]{6}$").matches(text)) return Color(0xFFFFE0A8)
    val rgb = text.removePrefix("#").toLong(16)
    return Color(0xFF000000L or rgb)
}

private fun personaColorOptions(): List<String> = listOf(
    "#FFE0A8",
    "#FFF1B8",
    "#DCEBFF",
    "#FFE2EA",
    "#E8DDFF",
    "#DFF5EC"
)

private fun sortedCalendarEvents(events: List<CalendarEvent>): List<CalendarEvent> =
    events.sortedByDescending { it.date }

private fun calendarDistanceText(date: String): String? {
    val eventDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
    return when (val days = ChronoUnit.DAYS.between(LocalDate.now(), eventDate)) {
        0L -> "今天"
        in 1L..Long.MAX_VALUE -> "还有 $days 天"
        else -> "已经过去 ${-days} 天"
    }
}

private fun dateText(value: String): String = formatIso(value, "yyyy-MM-dd")

private fun timeText(value: String): String = formatIso(value, "yyyy-MM-dd HH:mm")

private fun formatIso(value: String, pattern: String): String {
    val date = parseIso(value) ?: return ""
    return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
}

private fun parseIso(value: String): java.util.Date? {
    if (value.isBlank()) return null
    val formats = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'")
    return formats.firstNotNullOfOrNull { pattern ->
        runCatching {
            SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)
        }.getOrNull()
    }
}

private fun nowIsoText(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(java.util.Date())

private fun distanceText(distance: DistanceState?): String =
    if (distance?.available == true && distance.kilometers != null) "相隔约 ${distance.kilometers} 公里" else "等两个人都打开后显示距离"

private fun locationText(location: LocationSummary): String =
    listOf(location.province, location.city).filter { it.isNotBlank() }.distinct().joinToString(" / ").ifBlank { "等待更新位置" }

private fun memorySourceText(sourceType: String): String = when {
    sourceType == "document" -> "来自回忆文档"
    sourceType == "calendar" -> "来自日历"
    sourceType == "note" -> "来自留言"
    sourceType.contains("chat") -> "来自聊天"
    sourceType.contains("document") || sourceType.contains("calendar") || sourceType.contains("note") || sourceType == "mixed" -> "来自多个来源"
    else -> "来自聊天"
}

internal fun albumQuotaText(quota: AlbumQuotaState?): String =
    quota?.let { "已用 ${sizeText(it.usedBytes)} / ${sizeText(it.limitBytes)}" } ?: "相册空间 200兆"

internal fun sizeText(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return if (mb < 10) String.format("%.1f兆", mb) else "${mb.toInt()}兆"
}

private fun todayText(): String = LocalDate.now().toString()

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
