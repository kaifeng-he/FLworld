package com.hkfcl.world

import android.Manifest
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
            secondary = Color(0xFF7B6EA8),
            tertiary = Color(0xFF3F8F86),
            background = Color(0xFFFFF8F8),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF7E8EC)
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

    var baseUrl by remember { mutableStateOf(prefs.getString("baseUrl", "http://10.0.2.2:8787") ?: "http://10.0.2.2:8787") }
    var token by remember { mutableStateOf(prefs.getString("token", null)) }
    var userId by remember { mutableStateOf(prefs.getString("userId", "hkf") ?: "hkf") }
    var code by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(Tab.Chat) }
    var personas by remember { mutableStateOf(emptyList<Persona>()) }
    var sessions by remember { mutableStateOf(emptyList<ChatSession>()) }
    var selectedSession by remember { mutableStateOf<ChatSession?>(null) }
    var messages by remember { mutableStateOf(emptyList<ChatMessage>()) }
    var features by remember { mutableStateOf(emptyList<Feature>()) }
    var distance by remember { mutableStateOf<DistanceState?>(null) }
    var status by remember { mutableStateOf("") }
    val api = remember(baseUrl, token) { ApiClient(baseUrl, token) }

    fun refreshAll() {
        scope.launch {
            runCatching {
                personas = api.personas()
                sessions = api.sessions()
                features = api.features()
                distance = api.distance()
            }.onFailure { status = it.message ?: "同步失败" }
        }
    }

    fun syncLocation() {
        scope.launch {
            val location = locationHelper.currentCoarseLocation()
            if (location == null) {
                status = "定位不可用，距离会在授权后显示"
                return@launch
            }
            runCatching {
                api.updateLocation(location.first, location.second)
                distance = api.distance()
            }.onFailure { status = it.message ?: "位置同步失败" }
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
            baseUrl = baseUrl,
            userId = userId,
            code = code,
            status = status,
            onBaseUrl = { baseUrl = it },
            onUserId = { userId = it },
            onCode = { code = it },
            onLogin = {
                scope.launch {
                    runCatching { api.login(userId, code.ifBlank { userId }) }
                        .onSuccess { result ->
                            token = result.first
                            prefs.edit()
                                .putString("baseUrl", baseUrl)
                                .putString("token", result.first)
                                .putString("userId", result.second.id)
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
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.label) },
                        icon = { Text(item.label.take(1)) }
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
            Header(status)
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
                                .onFailure { status = it.message ?: "加载消息失败" }
                        }
                    },
                    onBackToSessions = {
                        selectedSession = null
                        messages = emptyList()
                    },
                    onCreateSession = { title, personaId ->
                        scope.launch {
                            runCatching { api.createSession(title, personaId) }
                                .onSuccess {
                                    selectedSession = it
                                    sessions = listOf(it) + sessions
                                    messages = emptyList()
                                }
                                .onFailure { status = it.message ?: "新建会话失败" }
                        }
                    },
                    onSend = { text ->
                        val session = selectedSession ?: return@ChatScreen
                        val pending = ChatMessage("local", session.id, "user", userId, text)
                        messages = messages + pending
                        scope.launch {
                            runCatching { api.sendMessage(session.id, text) }
                                .onSuccess {
                                    messages = api.messages(session.id)
                                    sessions = api.sessions()
                                }
                                .onFailure { status = it.message ?: "发送失败" }
                        }
                    }
                )
                Tab.Distance -> DistanceScreen(distance, onRefresh = {
                    if (locationHelper.hasPermission()) syncLocation() else locationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                })
                Tab.World -> WorldScreen(features)
                Tab.Settings -> SettingsScreen(
                    baseUrl = baseUrl,
                    userId = userId,
                    personas = personas,
                    onBaseUrl = {
                        baseUrl = it
                        prefs.edit().putString("baseUrl", it).apply()
                    },
                    onCreatePersona = { name, desc, memory ->
                        scope.launch {
                            runCatching { api.createPersona(name, desc, memory) }
                                .onSuccess { personas = personas + it }
                                .onFailure { status = it.message ?: "保存人格失败" }
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
    baseUrl: String,
    userId: String,
    code: String,
    status: String,
    onBaseUrl: (String) -> Unit,
    onUserId: (String) -> Unit,
    onCode: (String) -> Unit,
    onLogin: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("这是独属于 hkf 和 cl 的小世界", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(baseUrl, onBaseUrl, label = { Text("后端地址") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(userId, onUserId, label = { Text("身份：hkf 或 cl") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(code, onCode, label = { Text("登录口令") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("进入小世界") }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Header(status: String) {
    Column {
        Text("这是独属于 hkf 和 cl 的小世界", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("聊天、距离和之后慢慢加进来的回忆，都放在这里。", color = Color(0xFF6F5F66))
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
    onCreateSession: (String, String) -> Unit,
    onSend: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var selectedPersona by remember(personas) { mutableStateOf(personas.firstOrNull()?.id.orEmpty()) }

    if (selectedSession == null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PersonaPicker(personas, selectedPersona, onChange = { selectedPersona = it })
            OutlinedTextField(title, { title = it }, label = { Text("新会话标题") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = { onCreateSession(title.ifBlank { "新的聊天" }, selectedPersona.ifBlank { personas.firstOrNull()?.id.orEmpty() }) },
                enabled = personas.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("新建会话") }
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("同步会话") }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sessions) { session ->
                    Card(onClick = { onSelectSession(session) }, colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(session.title, fontWeight = FontWeight.SemiBold)
                            Text("由 ${session.createdBy} 创建", color = Color(0xFF766A70))
                        }
                    }
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackToSessions) { Text("会话") }
            TextButton(onClick = { onSelectSession(selectedSession) }) { Text("刷新") }
            Text(selectedSession.title, fontWeight = FontWeight.Bold)
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message ->
                val mine = message.senderId == currentUserId
                val label = when {
                    message.senderId == "bot" -> "机器人"
                    mine -> "我"
                    else -> "对方"
                }
                MessageBubble(label, message.text, mine, message.senderId == "bot")
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
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    bot -> Color(0xFFF0EBFF)
                    mine -> Color(0xFFFFE4EA)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaPicker(personas: List<Persona>, selected: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val name = personas.firstOrNull { it.id == selected }?.name ?: "选择人格"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = name,
            onValueChange = {},
            readOnly = true,
            label = { Text("会话人格") },
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
private fun DistanceScreen(distance: DistanceState?, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("两人的距离", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                if (distance?.available == true && distance.kilometers != null) {
                    Text("约 ${distance.kilometers} 公里", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                } else {
                    Text("等两个人都打开 App 后，就能显示大致距离。")
                }
            }
        }
        Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("更新距离") }
    }
}

@Composable
private fun WorldScreen(features: List<Feature>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("新功能待添加", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("这里会慢慢放进纪念日、相册、愿望清单、日记和更多只属于你们的内容。", color = Color(0xFF6F5F66))
        features.filter { it.status != "ready" }.forEach {
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(it.title, fontWeight = FontWeight.SemiBold)
                    Text("待添加", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    baseUrl: String,
    userId: String,
    personas: List<Persona>,
    onBaseUrl: (String) -> Unit,
    onCreatePersona: (String, String, String) -> Unit,
    onLogout: () -> Unit
) {
    var url by remember(baseUrl) { mutableStateOf(baseUrl) }
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var memory by remember { mutableStateOf("") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("当前身份：$userId")
        }
        item {
            OutlinedTextField(url, { url = it }, label = { Text("后端地址") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { onBaseUrl(url) }, modifier = Modifier.fillMaxWidth()) { Text("保存后端地址") }
        }
        item {
            Text("自定义人格", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(desc, { desc = it }, label = { Text("风格描述") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(memory, { memory = it }, label = { Text("长期记忆") }, modifier = Modifier.fillMaxWidth())
            Button(
                onClick = {
                    onCreatePersona(name, desc, memory)
                    name = ""
                    desc = ""
                    memory = ""
                },
                enabled = name.isNotBlank() && desc.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存人格") }
        }
        items(personas) { persona ->
            Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(persona.name, fontWeight = FontWeight.SemiBold)
                    Text(persona.description, color = Color(0xFF6F5F66))
                }
            }
        }
        item {
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("退出登录") }
        }
    }
}
