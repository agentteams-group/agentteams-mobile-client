import Foundation

public enum JSONValue: Codable, Sendable, Equatable {
    case string(String), number(Double), bool(Bool), object([String: JSONValue]), array([JSONValue]), null

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode([String: JSONValue].self) { self = .object(v) }
        else { self = .array(try c.decode([JSONValue].self)) }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }

    public var string: String? { if case .string(let s) = self { return s }; return nil }
    public var object: [String: JSONValue]? { if case .object(let o) = self { return o }; return nil }
    public var strings: [String] { if case .array(let a) = self { return a.compactMap(\.string) }; return [] }
}

public struct ServerAddress: Codable, Hashable, Sendable {
    public let base: URL

    public init(_ raw: String) throws {
        guard let c = URLComponents(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              ["http", "https"].contains(c.scheme?.lowercased()),
              let host = c.host, !host.isEmpty,
              c.user == nil, c.password == nil, c.query == nil, c.fragment == nil,
              let url = c.url else { throw APIError.invalidAddress }
        base = url
    }

    public func url(_ parts: [String], query: [String: String] = [:]) -> URL {
        var c = URLComponents(url: base, resolvingAgainstBaseURL: false)!
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        let prefix = c.percentEncodedPath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        c.percentEncodedPath = "/" + ([prefix].filter { !$0.isEmpty } + parts.map {
            $0.addingPercentEncoding(withAllowedCharacters: allowed)!
        }).joined(separator: "/")
        if !query.isEmpty { c.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) } }
        return c.url!
    }
}

public struct AccountSession: Codable, Identifiable, Sendable {
    public let id: UUID
    public let matrix: ServerAddress
    public let controller: ServerAddress
    public let accessToken: String
    public let userID: String

    public init(matrix: ServerAddress, controller: ServerAddress, accessToken: String, userID: String) {
        id = UUID(); self.matrix = matrix; self.controller = controller
        self.accessToken = accessToken; self.userID = userID
    }
}

public struct LoginResponse: Decodable, Sendable {
    public let access_token: String
    public let user_id: String
}

public struct Profile: Decodable, Sendable {
    public let name: String
    public let permissionLevel: Int
}

public struct Assistant: Decodable, Identifiable, Sendable {
    public let name: String
    public let phase: String?
    public let runtime: String?
    public let matrixUserID: String?
    public let roomID: String?
    public let role: String?
    public var id: String { matrixUserID ?? name }
}

public struct ManagerList: Decodable, Sendable { public let managers: [Assistant]? }
public struct WorkerList: Decodable, Sendable { public let workers: [Assistant]? }
public struct ProjectList: Decodable, Sendable { public let projects: [Project]? }
public struct Project: Decodable, Identifiable, Sendable {
    public let project_id: String
    public let title: String
    public let status: String
    public let team_id: String?
    public var id: String { "\(team_id ?? "")/\(project_id)" }
}

public enum TaskStage: String, CaseIterable, Sendable {
    case pending = "待开始", active = "进行中", review = "待验收", completed = "已完成", blocked = "受阻", cancelled = "已取消", unknown = "未知状态"
    public init(status: String) {
        switch status {
        case "pending", "planned", "planning": self = .pending
        case "assigned", "delegated", "acknowledged", "in_progress", "in-progress", "revision": self = .active
        case "submitted": self = .review
        case "completed": self = .completed
        case "blocked", "failed": self = .blocked
        case "cancelled", "canceled": self = .cancelled
        default: self = .unknown
        }
    }
}

public struct BoardTask: Identifiable, Sendable {
    public let id: String
    public let taskID: String
    public let projectID: String
    public let teamID: String?
    public let title: String
    public let projectTitle: String
    public let status: String
    public let assignee: String
    public let roomID: String?
    public let summary: String?
    public var stage: TaskStage { TaskStage(status: status) }
}

public struct Workflow: Decodable, Sendable {
    public let project_id: String
    public let title: String
    public let status: String
    public let team_id: String?
    public let source_room_id: String?
    public let reply_route: [String: JSONValue]?
    public let nodes: [Node]?
    public let tasks_detail: [Detail]?

    public struct Node: Decodable, Sendable {
        public let id: String
        public let name: String
        public let status: String
        public let assignee: String?
    }
    public struct Detail: Decodable, Sendable {
        public let task_id: String
        public let status: String?
        public let assigned_to: String?
        public let room_id: String?
        public let source_room_id: String?
        public let summary: String?
    }

    public var boardTasks: [BoardTask] {
        (nodes ?? []).map { node in
            let detail = tasks_detail?.first { $0.task_id == node.id }
            let room = [detail?.room_id, detail?.source_room_id, source_room_id, reply_route?["room_id"]?.string]
                .compactMap { $0 }.first { !$0.isEmpty }
            return BoardTask(id: "\(team_id ?? "")/\(project_id)/\(node.id)", taskID: node.id,
                             projectID: project_id, teamID: team_id, title: node.name, projectTitle: title,
                             status: detail?.status ?? node.status, assignee: detail?.assigned_to ?? node.assignee ?? "未分配",
                             roomID: room, summary: detail?.summary)
        }
    }
}

public struct AttentionResponse: Decodable, Sendable { public let items: [AttentionItem]? }
public struct AttentionItem: Decodable, Sendable {
    public let task_id: String
    public let task_title: String
    public let room_id: String?
    public let kind: String
    public let question: String
    public let event_id: String?
    public let resolved: Bool
}

public struct WorkItem: Identifiable, Sendable {
    public let id: String
    public let item: AttentionItem
    public let projectTitle: String
    public init(project: Project, item: AttentionItem) {
        id = "\(project.id)/\(item.task_id)/\(item.event_id ?? item.kind)"
        self.item = item; projectTitle = project.title
    }
}
