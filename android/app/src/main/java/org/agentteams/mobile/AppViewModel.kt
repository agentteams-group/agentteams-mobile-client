package org.agentteams.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import org.agentteams.mobile.core.*

data class RoomDestination(val id: String, val name: String, val target: String? = null)
data class AppState(
    val session: AccountSession? = null, val loadingSession: Boolean = true, val profile: Profile? = null,
    val matrix: MatrixState = MatrixState(), val managers: List<Assistant> = emptyList(), val workers: List<Assistant> = emptyList(),
    val tasks: List<BoardTask> = emptyList(), val attention: List<WorkItem> = emptyList(), val submitted: Set<String> = emptySet(),
    val loading: Boolean = false, val busy: Boolean = false, val sending: Boolean = false,
    val error: String? = null, val syncError: String? = null, val resourceErrors: List<String> = emptyList(),
    val selectedRoom: RoomDestination? = null, val selectedTask: String? = null,
) {
    val assistants: List<Assistant> get() = managers + workers
    val rooms: List<ChatRoom> get() = matrix.rooms.values.sortedByDescending { it.messages.lastOrNull()?.timestamp ?: 0 }
    val approvals: List<Approval> get() = pendingApprovals(matrix, assistants, session?.userId.orEmpty(), submitted)
    val questions: List<QuestionRequest> get() = rooms.flatMap { room ->
        room.messages.filter { it.sender != session?.userId && assistants.any { assistant -> assistant.matrixUserID == it.sender } }
            .flatMap { QuestionRequest.parse(it, room.id) }.filter { !it.expired() && !it.resolved(room.messages) }
    }
}

class AppViewModel(private val vault: SessionVault, private val api: ApiClient = ApiClient()) : ViewModel() {
    private val mutable = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private val refreshLock = Mutex()

    init {
        viewModelScope.launch {
            try {
                val saved = vault.load()
                mutable.update { it.copy(session = saved, loadingSession = false) }
            }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(loadingSession = false, error = "无法恢复登录，请重新登录：${error.localizedMessage}") }
            }
        }
    }

    private fun current(s: AccountSession) = mutable.value.session?.id == s.id
    private fun updateFor(s: AccountSession, update: (AppState) -> AppState) { if (current(s)) mutable.update(update) }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun openRoom(id: String, name: String? = null) {
        mutable.update { it.copy(selectedRoom = RoomDestination(id, name ?: it.matrix.rooms[id]?.name ?: "协作房间")) }
    }
    fun openTask(id: String) { mutable.update { it.copy(selectedTask = id) } }
    fun back() { mutable.update { if (it.selectedRoom != null) it.copy(selectedRoom = null) else it.copy(selectedTask = null) } }

    fun login(matrix: String, controller: String, username: String, password: String, completed: () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val session = api.login(matrix, controller, username, password)
                try { vault.save(session) }
                catch (error: Exception) {
                    withContext(NonCancellable) { withTimeoutOrNull(3000) { runCatching { api.logout(session) } } }
                    throw error
                }
                mutable.value = AppState(session = session, loadingSession = false)
                completed()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(error = error.localizedMessage ?: "登录失败") }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun logout() {
        val s = mutable.value.session ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                vault.clear()
                mutable.value = AppState(loadingSession = false)
                try { api.logout(s) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    if (mutable.value.session == null && !mutable.value.busy) mutable.update { it.copy(error = "本地登录已清除，服务器注销未完成：${error.localizedMessage}") }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(busy = false, error = error.localizedMessage) }
            }
        }
    }

    suspend fun runForeground() {
        state.map { it.session }.distinctUntilChanged().collectLatest { s ->
            if (s != null) coroutineScope {
                launch {
                    while (isActive && current(s)) {
                        try {
                            val response = api.sync(s, mutable.value.matrix.nextBatch)
                            updateFor(s) { it.copy(matrix = it.matrix.apply(response), syncError = null) }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            updateFor(s) { it.copy(syncError = error.localizedMessage) }
                            if ((error as? ApiException)?.status == 401) break
                            delay(3000)
                        }
                    }
                }
                launch { while (isActive && current(s)) { refreshNow(); delay(15_000) } }
            }
        }
    }

    fun refresh() { viewModelScope.launch { refreshNow() } }
    private suspend fun refreshNow() {
        val s = mutable.value.session ?: return
        if (!refreshLock.tryLock()) return
        updateFor(s) { it.copy(loading = true) }
        val errors = mutableListOf<String>()
        suspend fun <T> load(label: String, block: suspend () -> T): T? = try { block() }
            catch (error: Exception) { if (error is CancellationException) throw error; errors += "$label：${error.localizedMessage}"; null }
        try {
            load("账号") { api.me(s) }?.let { value -> updateFor(s) { it.copy(profile = value) } }
            load("Manager") { api.managers(s) }?.let { value -> updateFor(s) { it.copy(managers = value) } }
            load("助手") { api.workers(s) }?.let { value -> updateFor(s) { it.copy(workers = value) } }
            load("任务看板") { api.projects(s) }?.let { projects ->
                val tasks = mutableListOf<BoardTask>()
                val attention = mutableListOf<WorkItem>()
                projects.forEach { project ->
                    load("任务 ${project.title}") { api.workflow(s, project) }?.let { tasks += it.boardTasks }
                    load("待办 ${project.title}") { api.attention(s, project) }?.let { items -> attention += items.filter { !it.resolved }.map { WorkItem(project, it) } }
                    if (!current(s)) return
                }
                updateFor(s) { it.copy(tasks = tasks, attention = attention) }
            }
            updateFor(s) { it.copy(resourceErrors = errors) }
        } finally { updateFor(s) { it.copy(loading = false) }; refreshLock.unlock() }
    }

    private fun operation(block: suspend (AccountSession) -> Unit) {
        val s = mutable.value.session ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block(s) }
            catch (error: Exception) { if (error is CancellationException) throw error; updateFor(s) { it.copy(error = error.localizedMessage) } }
            finally { updateFor(s) { it.copy(busy = false) } }
        }
    }

    fun openAssistant(assistant: Assistant) = operation { s ->
        require(assistant.matrixUserID.isNotEmpty()) { "这个助手尚未完成初始化。" }
        val matrix = mutable.value.matrix
        val known = matrix.directRooms[assistant.matrixUserID]?.firstOrNull { it in matrix.rooms }
            ?: assistant.roomID.takeIf { it in matrix.rooms }
        val room = known ?: run {
            check(matrix.nextBatch != null) { "正在同步会话，请稍后再打开助手。" }
            val id = api.createDirectRoom(s, assistant.matrixUserID)
            updateFor(s) { state -> state.copy(matrix = state.matrix.copy(
                rooms = state.matrix.rooms + (id to ChatRoom(id, assistant.name)),
                directRooms = state.matrix.directRooms + (assistant.matrixUserID to (state.matrix.directRooms[assistant.matrixUserID].orEmpty() + id)))) }
            try { if (current(s)) api.setDirectRooms(s, mutable.value.matrix.directRooms) }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                updateFor(s) { it.copy(error = "对话已创建，会话列表同步失败：${error.localizedMessage}") }
            }
            id
        }
        updateFor(s) { it.copy(selectedRoom = RoomDestination(room, assistant.name, assistant.matrixUserID)) }
    }

    fun join(id: String) = operation { s ->
        api.join(s, id)
        updateFor(s) { state ->
            val name = state.matrix.invites[id] ?: id
            state.copy(matrix = state.matrix.copy(invites = state.matrix.invites - id,
                rooms = state.matrix.rooms + (id to (state.matrix.rooms[id] ?: ChatRoom(id, name)))), selectedRoom = RoomDestination(id, name))
        }
    }
    fun rejectInvite(id: String) = operation { s ->
        api.rejectInvite(s, id)
        updateFor(s) { it.copy(matrix = it.matrix.copy(invites = it.matrix.invites - id)) }
    }

    suspend fun loadRoom(id: String) {
        val s = mutable.value.session ?: return
        val events = api.roomState(s, id)
        updateFor(s) { it.copy(matrix = it.matrix.copy(rooms = it.matrix.rooms + (id to (it.matrix.rooms[id] ?: ChatRoom(id)).ingest(events)))) }
    }
    suspend fun older(id: String) {
        val s = mutable.value.session ?: return
        val from = mutable.value.matrix.rooms[id]?.previousBatch ?: return
        val page = api.messages(s, id, from)
        updateFor(s) { state -> state.copy(matrix = state.matrix.updateRoom(id) {
            it.ingest(page.state + page.chunk).copy(previousBatch = page.end.takeIf { page.chunk.isNotEmpty() && it != from })
        }) }
    }
    suspend fun send(room: String, text: String, target: String?, transactionId: String, extra: JsonObject = buildJsonObject {}): Boolean {
        val s = mutable.value.session ?: return false
        if (mutable.value.sending || mutable.value.matrix.rooms[room]?.encrypted != false) return false
        mutable.update { it.copy(sending = true) }
        try {
            val body = target?.let { "@${it.removePrefix("@").substringBefore(':')} $text" } ?: text
            api.send(s, room, body, transactionId, target, extra)
            return current(s)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            updateFor(s) { it.copy(error = error.localizedMessage) }; return false
        } finally { updateFor(s) { it.copy(sending = false) } }
    }
    suspend fun answer(question: QuestionRequest, selected: Map<String, Set<String>>, custom: Map<String, String>, transactionId: String): Boolean {
        val s = mutable.value.session ?: return false
        if (mutable.value.questions.none { it.id == question.id } || question.id in mutable.value.submitted) return false
        val response = question.response(selected, custom, s.userId) ?: return false
        val sent = send(question.roomId, response.first, question.sender, transactionId, response.second)
        if (sent) updateFor(s) { it.copy(submitted = it.submitted + question.id) }
        return sent
    }
    suspend fun answer(approval: Approval, approve: Boolean, transactionId: String): Boolean {
        val s = mutable.value.session ?: return false
        if (approval.expired()) { updateFor(s) { it.copy(error = "这个审批请求已超时，请刷新房间。") }; return false }
        val sent = send(approval.roomId, approval.command(approve), approval.sender, transactionId, approval.answerContent(approve, s.userId))
        if (sent) updateFor(s) { it.copy(submitted = it.submitted + approval.id) }
        return sent
    }
    suspend fun markRead(id: String) {
        val s = mutable.value.session ?: return
        val event = mutable.value.matrix.rooms[id]?.messages?.lastOrNull()?.id ?: return
        try {
            api.markRead(s, id, event)
            updateFor(s) { it.copy(matrix = it.matrix.updateRoom(id) { room -> room.copy(unread = 0, highlights = 0) }) }
        } catch (error: Exception) { if (error is CancellationException) throw error }
    }
}
