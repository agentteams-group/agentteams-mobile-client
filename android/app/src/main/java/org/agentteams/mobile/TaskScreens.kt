@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.agentteams.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import org.agentteams.mobile.core.*

@Composable fun TaskBoardScreen(state: AppState, model: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val width = minOf(320.dp, LocalConfiguration.current.screenWidthDp.dp - 40.dp)
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, label = { Text("搜索任务或项目") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        LazyRow(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(TaskStage.entries) { stage ->
                val tasks = state.tasks.filter { it.stage == stage && (query.isEmpty() || it.title.contains(query, true) || it.projectTitle.contains(query, true)) }
                Column(Modifier.width(width).fillMaxHeight().background(stage.color().copy(alpha = 0.07f), RoundedCornerShape(20.dp)).padding(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stage.label, color = stage.color(), style = MaterialTheme.typography.titleMedium)
                        Text("${tasks.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (tasks.isEmpty()) item { Text("暂无任务", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        items(tasks, key = { it.id }) { task ->
                            Card(onClick = { model.openTask(task.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                                    Text(task.projectTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(Icons.Default.Person, null, Modifier.size(18.dp)); Text(task.assignee, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable fun TaskDetailScreen(task: BoardTask?, model: AppViewModel) {
    BackHandler(onBack = model::back)
    Scaffold(topBar = { TopAppBar(title = { Text("任务详情") }, navigationIcon = {
        IconButton(onClick = model::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (task == null) Text("任务已更新，返回看板查看最新任务。")
            else {
                Text(task.title, style = MaterialTheme.typography.headlineSmall)
                Text(task.stage.label, color = task.stage.color())
                Text("项目：${task.projectTitle}")
                Text("负责人：${task.assignee}")
                Text("任务编号：${task.taskId}", style = MaterialTheme.typography.bodySmall)
                task.summary?.takeIf { it.isNotBlank() }?.let { SectionTitle("任务结果"); MarkdownText(it) }
                val roomId = task.roomId
                if (roomId != null) Button(onClick = { model.openRoom(roomId, task.title) }) { Icon(Icons.Default.Forum, null); Spacer(Modifier.width(8.dp)); Text("进入协作房间") }
                else Text("这个任务尚未关联协作房间，可与 Manager 对话跟进。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun TaskStage.color() = when (this) {
    TaskStage.PENDING, TaskStage.UNKNOWN, TaskStage.CANCELLED -> Color(0xFF777777)
    TaskStage.ACTIVE -> Color(0xFF2979C0)
    TaskStage.REVIEW -> Color(0xFFA35C00)
    TaskStage.COMPLETED -> Color(0xFF298C57)
    TaskStage.BLOCKED -> Color(0xFFBC4040)
}
