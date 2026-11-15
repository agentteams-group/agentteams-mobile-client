package org.agentteams.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

class ServerAddress(raw: String) {
    val base: HttpUrl
    init {
        val input = raw.trim()
        val url = input.toHttpUrlOrNull()
        require(url != null && url.username.isEmpty() && url.password.isEmpty() &&
            url.query == null && url.fragment == null && !input.substringAfter("://").substringBefore('/').contains('@')) {
            "请输入完整的 HTTP 或 HTTPS 服务地址，不要包含账号、密码或查询参数。"
        }
        base = url
    }
    fun url(path: List<String>, query: Map<String, String> = emptyMap()): HttpUrl = base.newBuilder().apply {
        // Preserve a reverse proxy prefix and remove only its trailing slash.
        if (base.encodedPath.endsWith('/')) removePathSegment(base.pathSize - 1)
        path.forEach(::addPathSegment)
        query.forEach { (key, value) -> addQueryParameter(key, value) }
    }.build()
}

@Serializable data class AccountSession(
    val matrix: String,
    val controller: String,
    val accessToken: String,
    val userId: String,
    val id: String = UUID.randomUUID().toString(),
)
@Serializable data class LoginResponse(val access_token: String, val user_id: String)
@Serializable data class Profile(val name: String, val permissionLevel: Int)
@Serializable data class Assistant(
    val name: String,
    val phase: String = "",
    val runtime: String = "",
    val matrixUserID: String = "",
    val roomID: String = "",
    val role: String = "",
) { val id: String get() = matrixUserID.ifEmpty { name } }
@Serializable data class ManagerList(val managers: List<Assistant> = emptyList())
@Serializable data class WorkerList(val workers: List<Assistant> = emptyList())
@Serializable data class ProjectList(val projects: List<Project> = emptyList())
@Serializable data class Project(val project_id: String, val title: String, val status: String, val team_id: String = "") {
    val id: String get() = "$team_id/$project_id"
    val scope: Map<String, String> get() = if (team_id.isEmpty()) emptyMap() else mapOf("team" to team_id)
}

enum class TaskStage(val label: String) {
    PENDING("待开始"), ACTIVE("进行中"), REVIEW("待验收"), COMPLETED("已完成"), BLOCKED("受阻"), CANCELLED("已取消"), UNKNOWN("未知状态");
    companion object {
        fun from(status: String): TaskStage = when (status) {
            "pending", "planned", "planning" -> PENDING
            "assigned", "delegated", "acknowledged", "in_progress", "in-progress", "revision" -> ACTIVE
            "submitted" -> REVIEW
            "completed" -> COMPLETED
            "blocked", "failed" -> BLOCKED
            "cancelled", "canceled" -> CANCELLED
            else -> UNKNOWN
        }
    }
}
data class BoardTask(
    val id: String, val taskId: String, val projectId: String, val teamId: String,
    val title: String, val projectTitle: String, val status: String,
    val assignee: String, val roomId: String?, val summary: String?,
) { val stage: TaskStage get() = TaskStage.from(status) }

@Serializable data class Workflow(
    val project_id: String, val title: String, val status: String,
    val team_id: String = "", val source_room_id: String = "",
    val reply_route: JsonObject = JsonObject(emptyMap()),
    val nodes: List<Node> = emptyList(), val tasks_detail: List<Detail> = emptyList(),
) {
    @Serializable data class Node(val id: String, val name: String, val status: String, val assignee: String = "")
    @Serializable data class Detail(
        val task_id: String, val status: String = "", val assigned_to: String = "",
        val room_id: String = "", val source_room_id: String = "", val summary: String? = null,
    )
    val boardTasks: List<BoardTask> get() = nodes.map { node ->
        val detail = tasks_detail.firstOrNull { it.task_id == node.id }
        BoardTask("$team_id/$project_id/${node.id}", node.id, project_id, team_id, node.name, title,
            detail?.status?.takeIf { it.isNotEmpty() } ?: node.status,
            listOf(detail?.assigned_to, node.assignee).firstOrNull { !it.isNullOrEmpty() } ?: "未分配",
            listOf(detail?.room_id, detail?.source_room_id, source_room_id, reply_route.text("room_id")).firstOrNull { !it.isNullOrEmpty() },
            detail?.summary)
    }
}

@Serializable data class AttentionResponse(val items: List<AttentionItem> = emptyList())
@Serializable data class AttentionItem(
    val task_id: String, val task_title: String, val room_id: String = "", val kind: String,
    val question: String, val event_id: String = "", val resolved: Boolean,
)
data class WorkItem(val project: Project, val item: AttentionItem) {
    val id: String get() = "${project.id}/${item.task_id}/${item.event_id.ifEmpty { item.kind }}"
}
