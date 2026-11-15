package org.agentteams.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class ApiException(val status: Int, detail: String) : IOException(when (status) {
    401 -> "登录已失效，请退出后重新登录。"
    403 -> "当前账号没有访问权限：$detail"
    404 -> "服务未提供此接口，请确认地址和 AT 版本。$detail"
    else -> "请求失败（$status）：$detail"
})

class ApiClient {
    private val client = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun request(server: ServerAddress, path: List<String>, token: String? = null,
                        method: String = "GET", query: Map<String, String> = emptyMap(), body: JsonObject? = null): String = withContext(Dispatchers.IO) {
        val content = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(server.url(path, query)).header("Accept", "application/json")
            .apply { if (token != null) header("Authorization", "Bearer $token") }.method(method, content).build()
        val response = suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }
            })
        }
        response.use {
            val raw = it.body?.string() ?: "{}"
            if (!it.isSuccessful) {
                val error = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                throw ApiException(it.code, error?.text("error") ?: error?.text("message") ?: it.message)
            }
            raw
        }
    }

    suspend fun login(matrix: String, controller: String, username: String, password: String): AccountSession {
        val matrixAddress = ServerAddress(matrix)
        val controllerAddress = ServerAddress(controller)
        val body = buildJsonObject {
            put("type", "m.login.password")
            putJsonObject("identifier") { put("type", "m.id.user"); put("user", username.trim()) }
            put("password", password); put("initial_device_display_name", "AgentTeams Android")
        }
        val login = json.decodeFromString<LoginResponse>(request(matrixAddress, listOf("_matrix", "client", "v3", "login"), method = "POST", body = body))
        val session = AccountSession(matrixAddress.base.toString(), controllerAddress.base.toString(), login.access_token, login.user_id)
        try { me(session) }
        catch (error: Exception) {
            withContext(NonCancellable) { withTimeoutOrNull(3000) { runCatching { logout(session) } } }
            throw error
        }
        return session
    }

    private suspend fun controller(s: AccountSession, path: List<String>, query: Map<String, String> = emptyMap()): String =
        request(ServerAddress(s.controller), listOf("api", "v1") + path, s.accessToken, query = query)
    private suspend fun matrix(s: AccountSession, path: List<String>, method: String = "GET",
                               query: Map<String, String> = emptyMap(), body: JsonObject? = null): String =
        request(ServerAddress(s.matrix), listOf("_matrix", "client", "v3") + path, s.accessToken, method, query, body)

    suspend fun me(s: AccountSession): Profile = json.decodeFromString(controller(s, listOf("me")))
    suspend fun managers(s: AccountSession): List<Assistant> = json.decodeFromString<ManagerList>(controller(s, listOf("managers"))).managers
    suspend fun workers(s: AccountSession): List<Assistant> = json.decodeFromString<WorkerList>(controller(s, listOf("workers"))).workers
    suspend fun projects(s: AccountSession): List<Project> = json.decodeFromString<ProjectList>(controller(s, listOf("projects"))).projects
    suspend fun workflow(s: AccountSession, project: Project): Workflow =
        json.decodeFromString(controller(s, listOf("projects", project.project_id, "workflow"), project.scope + ("includeTasks" to "true")))
    suspend fun attention(s: AccountSession, project: Project): List<AttentionItem> =
        json.decodeFromString<AttentionResponse>(controller(s, listOf("projects", project.project_id, "attention"), project.scope)).items
    suspend fun sync(s: AccountSession, since: String?): SyncResponse {
        val query = mutableMapOf("timeout" to if (since == null) "0" else "30000",
            "filter" to """{"room":{"timeline":{"limit":50},"state":{"lazy_load_members":true}},"presence":{"types":[]}}""")
        since?.let { query["since"] = it }
        return json.decodeFromString(matrix(s, listOf("sync"), query = query))
    }
    suspend fun roomState(s: AccountSession, room: String): List<MatrixEvent> = json.decodeFromString(matrix(s, listOf("rooms", room, "state")))
    suspend fun messages(s: AccountSession, room: String, from: String): MessagePage =
        json.decodeFromString(matrix(s, listOf("rooms", room, "messages"), query = mapOf("dir" to "b", "limit" to "50", "from" to from)))
    suspend fun join(s: AccountSession, room: String) { matrix(s, listOf("join", room), "POST", body = buildJsonObject {}) }
    suspend fun rejectInvite(s: AccountSession, room: String) { matrix(s, listOf("rooms", room, "leave"), "POST", body = buildJsonObject {}) }
    suspend fun createDirectRoom(s: AccountSession, user: String): String {
        val response = matrix(s, listOf("createRoom"), "POST", body = buildJsonObject {
            put("is_direct", true); put("preset", "trusted_private_chat"); putJsonArray("invite") { add(user) }
        })
        return (json.parseToJsonElement(response) as JsonObject).text("room_id") ?: throw IOException("服务响应缺少房间编号。")
    }
    suspend fun setDirectRooms(s: AccountSession, rooms: Map<String, List<String>>) {
        matrix(s, listOf("user", s.userId, "account_data", "m.direct"), "PUT", body = buildJsonObject {
            rooms.forEach { (user, ids) -> putJsonArray(user) { ids.forEach { add(it) } } }
        })
    }
    suspend fun send(s: AccountSession, room: String, text: String, transactionId: String,
                     mention: String? = null, extra: JsonObject = buildJsonObject {}): String {
        val response = matrix(s, listOf("rooms", room, "send", "m.room.message", transactionId), "PUT", body = buildJsonObject {
            extra.forEach { (key, value) -> put(key, value) }
            put("msgtype", "m.text"); put("body", text)
            mention?.let { putJsonObject("m.mentions") { putJsonArray("user_ids") { add(it) } } }
        })
        return (json.parseToJsonElement(response) as JsonObject).text("event_id") ?: throw IOException("服务响应缺少消息编号。")
    }
    suspend fun markRead(s: AccountSession, room: String, event: String) {
        matrix(s, listOf("rooms", room, "read_markers"), "POST", body = buildJsonObject { put("m.fully_read", event); put("m.read", event) })
    }
    suspend fun logout(s: AccountSession) { matrix(s, listOf("logout"), "POST", body = buildJsonObject {}) }
}
