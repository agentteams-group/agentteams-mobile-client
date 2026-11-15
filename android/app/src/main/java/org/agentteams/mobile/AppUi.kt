@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.agentteams.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import org.agentteams.mobile.core.*

private val green = Color(0xFF298C57)

@Composable fun AppRoot(model: AppViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(model, owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.runForeground() } }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8DD7AB), primaryContainer = Color(0xFF164B2F), secondaryContainer = Color(0xFF164B2F))
        else lightColorScheme(primary = green, primaryContainer = Color(0xFFDFF3E7), secondaryContainer = Color(0xFFDFF3E7))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            when {
                state.loadingSession -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.session == null -> LoginScreen(state.busy, model::login)
                state.selectedRoom != null -> ChatScreen(state, model, state.selectedRoom!!)
                state.selectedTask != null -> TaskDetailScreen(state.tasks.firstOrNull { it.id == state.selectedTask }, model)
                else -> Dashboard(state, model)
            }
            state.error?.let { error ->
                AlertDialog(onDismissRequest = model::dismissError, title = { Text("操作未完成") }, text = { Text(error) },
                    confirmButton = { TextButton(onClick = model::dismissError) { Text("知道了") } })
            }
        }
    }
}

@Composable internal fun LoginScreen(busy: Boolean, login: (String, String, String, String, () -> Unit) -> Unit) {
    var matrix by rememberSaveable { mutableStateOf("") }
    var controller by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Deliberately not saveable: passwords never enter saved instance state.
    var password by remember { mutableStateOf("") }
    val valid = matrix.isNotBlank() && controller.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Default.Forum, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(46.dp))
        Text("AgentTeams Mobile", style = MaterialTheme.typography.titleLarge)
        Text("和你的 Agent 团队\n一起推进工作", style = MaterialTheme.typography.headlineLarge)
        Text("登录 AT，继续对话、跟进任务、参与协作。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(matrix, { matrix = it }, label = { Text("Matrix 地址") }, placeholder = { Text("https://matrix.example.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(controller, { controller = it }, label = { Text("Controller 地址") }, placeholder = { Text("https://at.example.com") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(username, { username = it }, label = { Text("用户名或 Matrix ID") },
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { login(matrix, controller, username, password) { password = "" } }, enabled = valid && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("登录")
        }
        Text("填写设备能访问的服务地址，沿用现有 AT 账号。真机的 localhost 指向手机自身；Android 模拟器可用 10.0.2.2 访问开发电脑。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun Dashboard(state: AppState, model: AppViewModel) {
    var tab by rememberSaveable(state.session?.id) { mutableIntStateOf(0) }
    var account by remember { mutableStateOf(false) }
    val titles = listOf("工作台", "我的助手", "任务看板", "协作房间")
    val icons = listOf(Icons.Default.Dashboard, Icons.Default.AutoAwesome, Icons.Default.ViewKanban, Icons.Default.Forum)
    Scaffold(topBar = {
        TopAppBar(title = { Text(titles[tab]) }, actions = {
            IconButton(onClick = model::refresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, "刷新") }
            IconButton(onClick = { account = true }) { Icon(Icons.Default.AccountCircle, "账号与连接") }
        })
    }, bottomBar = {
        NavigationBar { titles.forEachIndexed { index, title ->
            NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], null) }, label = { Text(title) })
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.syncError?.let { ErrorBanner("会话同步：$it") }
            ResourceErrors(state.resourceErrors)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> WorkbenchScreen(state, model)
                    1 -> AssistantsScreen(state, model)
                    2 -> TaskBoardScreen(state, model)
                    3 -> RoomsScreen(state, model)
                }
            }
        }
    }
    if (account) AccountSheet(state, model) { account = false }
}

@Composable private fun AccountSheet(state: AppState, model: AppViewModel, close: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = close) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("账号与连接", style = MaterialTheme.typography.titleLarge)
            Text(state.profile?.name ?: "同步中")
            Text(state.session?.userId.orEmpty())
            Text("Matrix", style = MaterialTheme.typography.labelLarge)
            Text(state.session?.matrix.orEmpty())
            Text("Controller", style = MaterialTheme.typography.labelLarge)
            Text(state.session?.controller.orEmpty())
            Text("登录凭据由 Android Keystore 加密保护，密码不会保存。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { confirm = true }, enabled = !state.busy) { Text("退出登录") }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("退出当前账号？") },
        text = { Text("将清除这台设备保存的登录凭据。") },
        confirmButton = { TextButton(onClick = { confirm = false; close(); model.logout() }) { Text("退出登录") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } })
}

@Composable private fun WorkbenchScreen(state: AppState, model: AppViewModel) {
    val mentions = state.rooms.filter { it.highlights > 0 }
    val approvals = state.approvals
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("你好，${state.profile?.name.orEmpty()}", style = MaterialTheme.typography.headlineSmall)
            Text("需要你参与的工作，都在这里。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.managers.firstOrNull()?.let { manager -> item { Button(onClick = { model.openAssistant(manager) }, enabled = !state.busy) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("与 Manager 对话") } } }
        item { SectionTitle("待处理事项 · ${state.attention.size}") }
        if (state.attention.isEmpty()) item { EmptyRow("没有等待你答复的任务") }
        items(state.attention, key = { it.id }) { work ->
            OutlinedCard(onClick = { model.openRoom(work.item.room_id, work.item.task_title) }, enabled = work.item.room_id.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(work.item.question)
                    Text("${work.project.title} · ${work.item.task_title}", style = MaterialTheme.typography.bodySmall)
                    if (work.item.room_id.isEmpty()) Text("尚未关联房间，可与 Manager 对话跟进。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionTitle("待审批 · ${approvals.size}") }
        if (approvals.isEmpty()) item { EmptyRow("没有待处理的工具审批") }
        items(approvals, key = { it.id }) { approval ->
            OutlinedCard(onClick = { model.openRoom(approval.roomId) }, modifier = Modifier.fillMaxWidth()) {
                ListItem(headlineContent = { Text(approval.tool) }, leadingContent = { Icon(Icons.Default.PanTool, null) }, supportingContent = { Text("进入房间查看并处理") })
            }
        }
        item { SectionTitle("房间邀请 · ${state.matrix.invites.size}") }
        if (state.matrix.invites.isEmpty()) item { EmptyRow("没有新的房间邀请") }
        items(state.matrix.invites.entries.toList(), key = { it.key }) { invite ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(invite.value)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { model.join(invite.key) }, enabled = !state.busy) { Text("加入房间") }
                        OutlinedButton(onClick = { model.rejectInvite(invite.key) }, enabled = !state.busy) { Text("拒绝") }
                    }
                }
            }
        }
        item { SectionTitle("@我的提醒") }
        if (mentions.isEmpty()) item { EmptyRow("没有未读的 @提醒") }
        items(mentions, key = { it.id }) { room ->
            ListItem(headlineContent = { Text(room.name) }, trailingContent = { Badge { Text("${room.highlights}") } },
                modifier = Modifier.clickable { model.openRoom(room.id) })
        }
    }
}

@Composable private fun AssistantsScreen(state: AppState, model: AppViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Manager") }
        if (state.managers.isEmpty()) item { EmptyRow("没有可访问的 Manager") }
        items(state.managers, key = { it.id }) { AssistantCard(it, state.busy) { model.openAssistant(it) } }
        item { SectionTitle("我的助手") }
        if (state.workers.isEmpty()) item { EmptyRow("还没有可用助手，可通过 Manager 创建。") }
        items(state.workers, key = { it.id }) { AssistantCard(it, state.busy) { model.openAssistant(it) } }
    }
}

@Composable private fun AssistantCard(assistant: Assistant, busy: Boolean, open: () -> Unit) {
    OutlinedCard(onClick = open, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text(assistant.name) }, leadingContent = { Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary) },
            supportingContent = { Text(listOf(assistant.runtime, assistant.phase).filter { it.isNotEmpty() }.joinToString(" · ")) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) })
    }
}

@Composable private fun RoomsScreen(state: AppState, model: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索房间") }, leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(16.dp), singleLine = true)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp)) {
            if (state.rooms.isEmpty()) item { EmptyRow("还没有协作房间。从助手开始对话，或在工作台接受邀请。") }
            items(state.rooms.filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }, key = { it.id }) { room ->
                ListItem(headlineContent = { Text(room.name, maxLines = 1) },
                    supportingContent = { Text(if (room.encrypted) "加密房间" else room.messages.lastOrNull()?.body ?: "还没有消息", maxLines = 2) },
                    leadingContent = { Icon(if (room.encrypted) Icons.Default.Lock else Icons.Default.Forum, null) },
                    trailingContent = { if (room.unread > 0) Badge { Text("${room.unread}") } }, modifier = Modifier.clickable { model.openRoom(room.id) })
            }
        }
    }
}

@Composable fun SectionTitle(title: String) { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
@Composable private fun EmptyRow(text: String) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp)) }
@Composable fun ErrorBanner(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp))
}
@Composable private fun ResourceErrors(errors: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    if (errors.isNotEmpty()) Column {
        TextButton(onClick = { expanded = !expanded }) { Text("部分数据暂不可用（${errors.size}）") }
        if (expanded) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) { errors.forEach { ErrorBanner(it) } }
    }
}
