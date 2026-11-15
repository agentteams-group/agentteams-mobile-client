@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.agentteams.mobile

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.agentteams.mobile.core.*
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable fun ChatScreen(state: AppState, model: AppViewModel, destination: RoomDestination) {
    val room = state.matrix.rooms[destination.id]
    val messages = remember(room?.events) { room?.messages.orEmpty() }
    val approvals = state.approvals.filter { it.roomId == destination.id }.associateBy { it.eventId }
    val assistants = state.assistants.filter { it.matrixUserID.isNotEmpty() && (it.matrixUserID in room?.members.orEmpty() || it.matrixUserID == destination.target) }
    var ready by remember(destination.id) { mutableStateOf(false) }
    var roomError by remember(destination.id) { mutableStateOf<String?>(null) }
    var target by rememberSaveable(destination.id) { mutableStateOf(destination.target.orEmpty()) }
    var targetInitialized by rememberSaveable(destination.id) { mutableStateOf(false) }
    var draft by rememberSaveable(destination.id) { mutableStateOf("") }
    var transaction by rememberSaveable(destination.id) { mutableStateOf(UUID.randomUUID().toString()) }
    var loadingHistory by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    BackHandler(onBack = model::back)

    suspend fun prepare() {
        ready = false; roomError = null
        try {
            model.loadRoom(destination.id); ready = true
            val current = model.state.value
            val candidates = current.assistants.filter { it.matrixUserID.isNotEmpty() && it.matrixUserID in current.matrix.rooms[destination.id]?.members.orEmpty() }
            if (!targetInitialized) {
                if (target.isEmpty() && candidates.size == 1) target = candidates[0].matrixUserID
                targetInitialized = true
            }
            model.markRead(destination.id)
        } catch (error: Exception) { if (error is CancellationException) throw error; roomError = error.localizedMessage }
    }
    LaunchedEffect(destination.id) { prepare() }
    LaunchedEffect(room?.id) { if (room == null && ready) { ready = false; roomError = "你已离开这个房间。" } }
    LaunchedEffect(messages.lastOrNull()?.id) {
        if (messages.isNotEmpty()) {
            if (list.firstVisibleItemIndex <= 1) {
                list.animateScrollToItem(0)
                model.markRead(destination.id)
            }
        }
    }

    Scaffold(modifier = Modifier.imePadding(), topBar = {
        TopAppBar(title = { Text(destination.name, maxLines = 1) }, navigationIcon = { IconButton(onClick = model::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } })
    }, bottomBar = {
        if (room?.encrypted != true) Surface(tonalElevation = 3.dp) {
            Column(Modifier.navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (assistants.isNotEmpty()) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menu = true }) { Text(assistants.firstOrNull { it.matrixUserID == target }?.let { "发送给 @${it.name}" } ?: "发送到房间") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("发送到房间") }, onClick = { target = ""; transaction = UUID.randomUUID().toString(); menu = false })
                            assistants.forEach { assistant -> DropdownMenuItem(text = { Text("@${assistant.name}") }, onClick = { target = assistant.matrixUserID; transaction = UUID.randomUUID().toString(); menu = false }) }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(draft, { draft = it; transaction = UUID.randomUUID().toString() }, placeholder = { Text("输入消息…") }, maxLines = 6,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(22.dp))
                    FilledIconButton(onClick = {
                        val text = draft.trim(); val sentDraft = draft; val sentTarget = target; val id = transaction
                        scope.launch {
                            if (model.send(destination.id, text, sentTarget.ifEmpty { null }, id)) {
                                if (draft == sentDraft) draft = ""
                                transaction = UUID.randomUUID().toString()
                                list.animateScrollToItem(0)
                            }
                        }
                    }, enabled = ready && !state.sending && draft.isNotBlank(), modifier = Modifier.size(52.dp)) {
                        if (state.sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.ArrowUpward, "发送消息")
                    }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.syncError?.let { ErrorBanner("会话同步：$it") }
            roomError?.let { error ->
                ErrorBanner(error)
                Row {
                    TextButton(onClick = { scope.launch { prepare() } }) { Text("重试") }
                    if (!ready) TextButton(onClick = { model.join(destination.id) }, enabled = !state.busy) { Text("加入此房间") }
                }
            }
            if (room?.encrypted == true) {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(Icons.Default.Lock, null, Modifier.size(40.dp))
                    Text("这个房间已启用端到端加密", style = MaterialTheme.typography.titleMedium)
                    Text("请在支持加密的 Matrix 客户端中继续对话。")
                }
            } else {
                if (!ready && roomError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(messages.asReversed(), key = { it.id }) { message -> MessageBubble(message, state, approvals[message.id], model) }
                    if (room?.previousBatch != null) item {
                        TextButton(onClick = {
                            loadingHistory = true
                            scope.launch {
                                try { model.older(destination.id) }
                                catch (error: Exception) { if (error is CancellationException) throw error; roomError = error.localizedMessage }
                                finally { loadingHistory = false }
                            }
                        }, enabled = !loadingHistory, modifier = Modifier.fillMaxWidth()) { Text(if (loadingHistory) "加载中…" else "加载更早消息") }
                    }
                }
            }
        }
    }
    LaunchedEffect(state.busy) {
        if (!state.busy && !ready && room != null && roomError != null) prepare()
    }
}

@Composable private fun MessageBubble(message: ChatMessage, state: AppState, approval: Approval?, model: AppViewModel) {
    val mine = message.sender == state.session?.userId
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.9f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(message.sender, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)), style = MaterialTheme.typography.labelSmall)
            }
            Surface(shape = RoundedCornerShape(16.dp), color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                MarkdownText(message.body, Modifier.padding(14.dp))
            }
            if (approval != null) ApprovalCard(approval, state.sending, model)
            if (approval == null && message.content.obj("org.agentteams.approval")?.text("status") == "pending" &&
                Approval.parse(message, state.selectedRoom?.id.orEmpty())?.id in state.submitted) Text("已提交，等待运行时确认", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun ApprovalCard(approval: Approval, sending: Boolean, model: AppViewModel) {
    var choice by remember { mutableStateOf<Boolean?>(null) }
    val approveId = rememberSaveable(approval.id) { UUID.randomUUID().toString() }
    val rejectId = rememberSaveable(approval.id) { UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("工具审批 · ${approval.tool}", style = MaterialTheme.typography.titleSmall)
            if (approval.parameters.isNotEmpty()) Text(approval.parameters, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { choice = true }, enabled = !sending) { Text("批准本次") }
                OutlinedButton(onClick = { choice = false }, enabled = !sending) { Text("拒绝") }
            }
        }
    }
    choice?.let { approve ->
        AlertDialog(onDismissRequest = { choice = null }, title = { Text(if (approve) "批准这次工具调用？" else "拒绝这次工具调用？") },
            text = { Text("仅处理请求 ${approval.requestId}，结果将发送到当前协作房间。") },
            confirmButton = { TextButton(onClick = { choice = null; scope.launch { model.answer(approval, approve, if (approve) approveId else rejectId) } }) { Text(if (approve) "批准本次" else "拒绝本次") } },
            dismissButton = { TextButton(onClick = { choice = null }) { Text("取消") } })
    }
}

@Composable fun MarkdownText(body: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) { Markwon.create(context) }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    AndroidView(modifier = modifier.fillMaxWidth(), factory = { TextView(it).apply {
        textSize = 16f; setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance()
    } }, update = { view -> view.setTextColor(color); markwon.setMarkdown(view, body) })
}
