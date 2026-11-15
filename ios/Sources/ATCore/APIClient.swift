import Foundation

public enum APIError: LocalizedError {
    case invalidAddress
    case http(Int, String)
    case invalidResponse
    public var errorDescription: String? {
        switch self {
        case .invalidAddress: return "请输入完整的 http:// 或 https:// 服务地址，不要包含账号、密码或查询参数。"
        case .http(let status, let detail):
            if status == 401 { return "登录已失效，请退出后重新登录。" }
            if status == 403 { return "当前账号没有访问权限：\(detail)" }
            if status == 404 { return "服务未提供此接口，请确认地址和 AT 版本。\(detail)" }
            return "请求失败（\(status)）：\(detail)"
        case .invalidResponse: return "服务返回了无法识别的响应。"
        }
    }
    public var isUnauthorized: Bool { if case .http(401, _) = self { return true }; return false }
}

// Never forward a login body or a bearer token through a redirect.
private final class NoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

public final class APIClient: @unchecked Sendable {
    private let transport: URLSession
    public init(transport: URLSession? = nil) {
        if let transport { self.transport = transport }
        else {
            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 45
            config.timeoutIntervalForResource = 60
            self.transport = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
        }
    }

    public func request<T: Decodable>(_ type: T.Type, server: ServerAddress, path: [String],
                                      token: String? = nil, method: String = "GET",
                                      query: [String: String] = [:], body: [String: JSONValue]? = nil) async throws -> T {
        var request = URLRequest(url: server.url(path, query: query))
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        let (data, response) = try await transport.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            let json = try? JSONDecoder().decode([String: JSONValue].self, from: data)
            throw APIError.http(http.statusCode, json?["error"]?.string ?? json?["message"]?.string ?? HTTPURLResponse.localizedString(forStatusCode: http.statusCode))
        }
        return try JSONDecoder().decode(T.self, from: data)
    }

    public func login(matrix: ServerAddress, controller: ServerAddress, username: String, password: String) async throws -> AccountSession {
        let login = try await request(LoginResponse.self, server: matrix, path: ["_matrix", "client", "v3", "login"], method: "POST", body: [
            "type": .string("m.login.password"),
            "identifier": .object(["type": .string("m.id.user"), "user": .string(username)]),
            "password": .string(password), "initial_device_display_name": .string("AgentTeams iOS"),
        ])
        let session = AccountSession(matrix: matrix, controller: controller, accessToken: login.access_token, userID: login.user_id)
        do { _ = try await me(session) }
        catch { try? await logout(session); throw error }
        return session
    }

    public func me(_ s: AccountSession) async throws -> Profile { try await controller(Profile.self, s, ["me"]) }
    public func managers(_ s: AccountSession) async throws -> ManagerList { try await controller(ManagerList.self, s, ["managers"]) }
    public func workers(_ s: AccountSession) async throws -> WorkerList { try await controller(WorkerList.self, s, ["workers"]) }
    public func projects(_ s: AccountSession) async throws -> ProjectList { try await controller(ProjectList.self, s, ["projects"]) }
    public func workflow(_ s: AccountSession, project: Project) async throws -> Workflow {
        var query = scope(project)
        query["includeTasks"] = "true"
        return try await controller(Workflow.self, s, ["projects", project.project_id, "workflow"], query: query)
    }
    public func attention(_ s: AccountSession, project: Project) async throws -> AttentionResponse {
        try await controller(AttentionResponse.self, s, ["projects", project.project_id, "attention"], query: scope(project))
    }
    private func scope(_ project: Project) -> [String: String] {
        guard let team = project.team_id, !team.isEmpty else { return [:] }
        return ["team": team]
    }
    private func controller<T: Decodable>(_ type: T.Type, _ s: AccountSession, _ path: [String], query: [String: String] = [:]) async throws -> T {
        try await request(type, server: s.controller, path: ["api", "v1"] + path, token: s.accessToken, query: query)
    }
    private func matrix<T: Decodable>(_ type: T.Type, _ s: AccountSession, _ path: [String], method: String = "GET",
                                      query: [String: String] = [:], body: [String: JSONValue]? = nil) async throws -> T {
        try await request(type, server: s.matrix, path: ["_matrix", "client", "v3"] + path,
                          token: s.accessToken, method: method, query: query, body: body)
    }
    public func sync(_ s: AccountSession, since: String?) async throws -> SyncResponse {
        var query = ["timeout": since == nil ? "0" : "30000",
                     "filter": #"{"room":{"timeline":{"limit":50},"state":{"lazy_load_members":true}},"presence":{"types":[]}}"#]
        if let since { query["since"] = since }
        return try await matrix(SyncResponse.self, s, ["sync"], query: query)
    }
    public func messages(_ s: AccountSession, room: String, from: String) async throws -> MessagePage {
        try await matrix(MessagePage.self, s, ["rooms", room, "messages"], query: ["dir": "b", "limit": "50", "from": from])
    }
    public func roomState(_ s: AccountSession, room: String) async throws -> [MatrixEvent] {
        try await matrix([MatrixEvent].self, s, ["rooms", room, "state"])
    }
    public func join(_ s: AccountSession, room: String) async throws {
        _ = try await matrix([String: JSONValue].self, s, ["join", room], method: "POST", body: [:])
    }
    public func rejectInvite(_ s: AccountSession, room: String) async throws {
        _ = try await matrix([String: JSONValue].self, s, ["rooms", room, "leave"], method: "POST", body: [:])
    }
    public func createDirectRoom(_ s: AccountSession, userID: String) async throws -> String {
        let result = try await matrix([String: JSONValue].self, s, ["createRoom"], method: "POST", body: [
            "is_direct": .bool(true), "preset": .string("trusted_private_chat"), "invite": .array([.string(userID)]),
        ])
        guard let id = result["room_id"]?.string else { throw APIError.invalidResponse }
        return id
    }
    public func setDirectRooms(_ s: AccountSession, rooms: [String: [String]]) async throws {
        _ = try await matrix([String: JSONValue].self, s, ["user", s.userID, "account_data", "m.direct"], method: "PUT",
                             body: rooms.mapValues { .array($0.map(JSONValue.string)) })
    }
    public func send(_ s: AccountSession, room: String, body: String, transactionID: String,
                     mention: String? = nil, extra: [String: JSONValue] = [:]) async throws -> String {
        var content = extra
        content["msgtype"] = .string("m.text")
        content["body"] = .string(body)
        if let mention { content["m.mentions"] = .object(["user_ids": .array([.string(mention)])]) }
        let result = try await matrix([String: JSONValue].self, s, ["rooms", room, "send", "m.room.message", transactionID], method: "PUT", body: content)
        guard let id = result["event_id"]?.string else { throw APIError.invalidResponse }
        return id
    }
    public func markRead(_ s: AccountSession, room: String, event: String) async throws {
        _ = try await matrix([String: JSONValue].self, s, ["rooms", room, "read_markers"], method: "POST",
                             body: ["m.fully_read": .string(event), "m.read": .string(event)])
    }
    public func logout(_ s: AccountSession) async throws {
        _ = try await matrix([String: JSONValue].self, s, ["logout"], method: "POST", body: [:])
    }
}
