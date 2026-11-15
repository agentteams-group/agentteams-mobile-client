import Foundation
import Observation
import ATCore

@MainActor @Observable
public final class AppStore {
    public private(set) var session: AccountSession?
    var profile: Profile?
    var matrix = MatrixState()
    var managers: [Assistant] = []
    var workers: [Assistant] = []
    var tasks: [BoardTask] = []
    var attention: [WorkItem] = []
    var resourceErrors: [String] = []
    var syncError: String?
    var error: String?
    var loading = false
    var authenticating = false
    var sending = false
    var openingAssistant = false
    var selectedRoom: RoomDestination?
    var selectedTab = 0
    var submittedApprovals: Set<String> = []
    @ObservationIgnored private let api = APIClient()
    @ObservationIgnored private let vault = SessionVault()
    @ObservationIgnored private var refreshing = false

    public init() {
        do { session = try vault.load() }
        catch { self.error = error.localizedDescription }
    }

    var rooms: [ChatRoom] { matrix.rooms.values.sorted { $0.lastActivity > $1.lastActivity } }
    var allAssistants: [Assistant] { managers + workers }
    var approvals: [Approval] {
        rooms.flatMap { room in
            room.messages.compactMap { message -> Approval? in
                guard message.sender != session?.userID,
                      allAssistants.contains(where: { $0.matrixUserID == message.sender }),
                      let approval = Approval(message: message, roomID: room.id),
                      !approval.expired, !submittedApprovals.contains(approval.id) else { return nil }
                // A terminal event for this exact request supersedes its pending card.
                let resolved = room.messages.contains { other in
                    guard other.sender == message.sender,
                          let data = other.content["org.agentteams.approval"]?.object else { return false }
                    return data["request_id"]?.string == approval.requestID && data["runtime"]?.string == approval.runtime
                        && (data["context_id"]?.string ?? "") == approval.contextID
                        && ["approved", "rejected", "cancelled", "expired"].contains(data["status"]?.string ?? "")
                }
                let answered = room.messages.contains { other in
                    other.sender == session?.userID &&
                    other.content["m.relates_to"]?.object?["m.in_reply_to"]?.object?["event_id"]?.string == message.id &&
                    other.content["com.agentteams.answer"]?.object?["confirmation_id"]?.string == approval.requestID
                }
                return resolved || answered ? nil : approval
            }
        }
    }

    func login(matrix rawMatrix: String, controller rawController: String, username: String, password: String) async -> Bool {
        guard !authenticating else { return false }
        authenticating = true; error = nil
        defer { authenticating = false }
        do {
            let s = try await api.login(matrix: ServerAddress(rawMatrix), controller: ServerAddress(rawController),
                                        username: username.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
            do { try vault.save(s) }
            catch { try? await api.logout(s); throw error }
            session = s
            return true
        } catch { self.error = error.localizedDescription; return false }
    }

    func logout() async {
        guard let s = session else { return }
        do { try vault.clear() }
        catch { self.error = error.localizedDescription; return }
        session = nil; profile = nil; matrix = MatrixState()
        managers = []; workers = []; tasks = []; attention = []; submittedApprovals = []
        selectedRoom = nil; resourceErrors = []; syncError = nil; error = nil; selectedTab = 0
        do { try await api.logout(s) }
        catch { self.error = "本地登录已清除，服务器注销未完成：\(error.localizedDescription)" }
    }

    func run() async {
        guard let s = session else { return }
        async let sync: Void = syncContinuously(s)
        async let resources: Void = refreshContinuously(s)
        _ = await (sync, resources)
    }

    private func syncContinuously(_ s: AccountSession) async {
        while !Task.isCancelled && session?.id == s.id {
            do {
                let response = try await api.sync(s, since: matrix.nextBatch)
                guard !Task.isCancelled, session?.id == s.id else { return }
                matrix.apply(response); syncError = nil
            } catch {
                guard !Task.isCancelled, session?.id == s.id else { return }
                syncError = error.localizedDescription
                if (error as? APIError)?.isUnauthorized == true { return }
                do { try await Task.sleep(for: .seconds(3)) } catch { return }
            }
        }
    }

    private func refreshContinuously(_ s: AccountSession) async {
        while !Task.isCancelled && session?.id == s.id {
            await refresh()
            do { try await Task.sleep(for: .seconds(15)) } catch { return }
        }
    }

    func refresh() async {
        guard let s = session, !refreshing else { return }
        refreshing = true; loading = true
        defer { refreshing = false; loading = false }
        var failures: [String] = []
        do {
            let value = try await api.me(s)
            guard session?.id == s.id, !Task.isCancelled else { return }
            profile = value
        } catch { failures.append("账号：\(error.localizedDescription)") }
        do {
            let value = try await api.managers(s)
            guard session?.id == s.id, !Task.isCancelled else { return }
            managers = value.managers ?? []
        } catch { failures.append("Manager：\(error.localizedDescription)") }
        do {
            let value = try await api.workers(s)
            guard session?.id == s.id, !Task.isCancelled else { return }
            workers = value.workers ?? []
        } catch { failures.append("助手：\(error.localizedDescription)") }
        do {
            let projects = try await api.projects(s).projects ?? []
            var nextTasks: [BoardTask] = []
            var nextAttention: [WorkItem] = []
            for project in projects {
                do {
                    let workflow = try await api.workflow(s, project: project)
                    nextTasks += workflow.boardTasks
                } catch { failures.append("任务 \(project.title)：\(error.localizedDescription)") }
                do {
                    let response = try await api.attention(s, project: project)
                    nextAttention += (response.items ?? []).filter { !$0.resolved }.map { WorkItem(project: project, item: $0) }
                } catch { failures.append("待办 \(project.title)：\(error.localizedDescription)") }
                guard session?.id == s.id, !Task.isCancelled else { return }
            }
            guard session?.id == s.id, !Task.isCancelled else { return }
            tasks = nextTasks; attention = nextAttention
        } catch { failures.append("任务看板：\(error.localizedDescription)") }
        guard session?.id == s.id, !Task.isCancelled else { return }
        resourceErrors = failures
    }

    func openAssistant(_ assistant: Assistant) async {
        guard let s = session, !openingAssistant else { return }
        openingAssistant = true
        defer { openingAssistant = false }
        do {
            guard let user = assistant.matrixUserID, !user.isEmpty else {
                throw APIError.http(409, "这个助手尚未完成初始化。")
            }
            let direct = matrix.directRooms[user]?.first { matrix.rooms[$0] != nil }
            // The resource's roomID can be an Admin/Manager control room. Only reuse it if this Human is already a member.
            let known = assistant.roomID.flatMap { matrix.rooms[$0] != nil ? $0 : nil }
            let room: String
            if let existing = direct ?? known { room = existing }
            else {
                guard matrix.nextBatch != nil else { throw APIError.http(409, "正在同步会话，请稍后再打开助手。") }
                room = try await api.createDirectRoom(s, userID: user)
                guard session?.id == s.id else { return }
                // Keep the created room locally even if m.direct persistence fails, so retry never creates a duplicate.
                matrix.rooms[room] = ChatRoom(id: room, name: assistant.name)
                matrix.directRooms[user, default: []].append(room)
                do { try await api.setDirectRooms(s, rooms: matrix.directRooms) }
                catch { self.error = "对话已创建，会话列表同步失败：\(error.localizedDescription)" }
            }
            guard session?.id == s.id else { return }
            selectedRoom = RoomDestination(id: room, name: assistant.name, target: user)
        } catch { self.error = error.localizedDescription }
    }

    func openRoom(_ id: String, name: String? = nil) {
        selectedRoom = RoomDestination(id: id, name: name ?? matrix.rooms[id]?.name ?? "协作房间", target: nil)
    }

    func join(_ id: String) async {
        guard let s = session else { return }
        do {
            try await api.join(s, room: id)
            guard session?.id == s.id else { return }
            let name = matrix.invites[id] ?? id
            matrix.invites.removeValue(forKey: id)
            matrix.rooms[id] = matrix.rooms[id] ?? ChatRoom(id: id, name: name)
            openRoom(id, name: name)
        } catch { self.error = error.localizedDescription }
    }

    func rejectInvite(_ id: String) async {
        guard let s = session else { return }
        do {
            try await api.rejectInvite(s, room: id)
            guard session?.id == s.id else { return }
            matrix.invites.removeValue(forKey: id)
        } catch { self.error = error.localizedDescription }
    }

    func loadRoom(_ id: String) async throws {
        guard let s = session else { return }
        let state = try await api.roomState(s, room: id)
        guard session?.id == s.id else { return }
        var room = matrix.rooms[id] ?? ChatRoom(id: id)
        room.ingest(state); matrix.rooms[id] = room
    }

    func olderMessages(_ id: String) async throws {
        guard let s = session, let from = matrix.rooms[id]?.previousBatch else { return }
        let page = try await api.messages(s, room: id, from: from)
        guard session?.id == s.id else { return }
        matrix.rooms[id]?.ingest(page.state ?? [])
        matrix.rooms[id]?.ingest(page.chunk ?? [])
        matrix.rooms[id]?.previousBatch = page.end == from || (page.chunk ?? []).isEmpty ? nil : page.end
    }

    func send(room: String, text: String, target: String?, transactionID: String, extra: [String: JSONValue] = [:]) async -> Bool {
        guard let s = session, !sending, matrix.rooms[room]?.encrypted == false else { return false }
        sending = true
        defer { sending = false }
        let body = target.map { "@\($0.dropFirst().split(separator: ":").first.map(String.init) ?? $0) \(text)" } ?? text
        do {
            _ = try await api.send(s, room: room, body: body, transactionID: transactionID, mention: target, extra: extra)
            return session?.id == s.id
        } catch { self.error = error.localizedDescription; return false }
    }

    func answer(_ approval: Approval, approve: Bool, transactionID: String) async -> Bool {
        guard let s = session, !approval.expired else { return false }
        let sent = await send(room: approval.roomID, text: approval.command(approve: approve), target: approval.sender,
                              transactionID: transactionID, extra: approval.answerContent(approve: approve, actor: s.userID))
        if sent { submittedApprovals.insert(approval.id) }
        return sent
    }

    func markRead(_ id: String) async {
        guard let s = session, let event = matrix.rooms[id]?.messages.last?.id else { return }
        do {
            try await api.markRead(s, room: id, event: event)
            guard session?.id == s.id else { return }
            matrix.rooms[id]?.unread = 0; matrix.rooms[id]?.highlights = 0
        } catch { /* A failed receipt must not mark the room read locally. */ }
    }
}

struct RoomDestination: Identifiable {
    let id: String
    let name: String
    let target: String?
}
