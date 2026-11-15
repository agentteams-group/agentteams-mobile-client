import Foundation

public struct Approval: Identifiable, Sendable {
    public let id: String
    public let eventID: String
    public let roomID: String
    public let sender: String
    public let tool: String
    public let parameters: String
    public let runtime: String
    public let requestID: String
    public let contextID: String
    public let expiresAt: Double?

    public init?(message: ChatMessage, roomID: String) {
        guard let data = message.content["org.agentteams.approval"]?.object,
              data["version"] == .number(1), data["status"]?.string == "pending",
              let runtime = data["runtime"]?.string,
              ["openclaw", "qwenpaw", "hermes", "deepseek-harness", "openhuman", "codex"].contains(runtime),
              let requestID = data["request_id"]?.string, Self.valid(requestID, colon: true),
              let tool = data["tool_name"]?.string, !tool.isEmpty else { return nil }
        let context = data["context_id"]?.string ?? ""
        if ["hermes", "deepseek-harness", "openhuman", "codex"].contains(runtime),
           !Self.valid(context, colon: runtime == "codex") { return nil }
        self.id = "\(roomID)/\(message.sender)/\(runtime)/\(context)/\(requestID)"
        self.eventID = message.id; self.roomID = roomID; sender = message.sender
        self.tool = tool; parameters = data["parameters"]?.string ?? ""
        self.runtime = runtime; self.requestID = requestID; contextID = context
        if case .number(let expires) = data["expires_at"] { expiresAt = expires } else { expiresAt = nil }
    }

    private static func valid(_ value: String, colon: Bool) -> Bool {
        !value.isEmpty && value.range(of: colon ? "^[A-Za-z0-9_.:-]+$" : "^[A-Za-z0-9_.-]+$", options: .regularExpression) != nil
    }
    public var expired: Bool { expiresAt.map { $0 <= Date().timeIntervalSince1970 * 1000 } ?? false }
    public func command(approve: Bool) -> String {
        switch runtime {
        case "openclaw": return "/approve \(requestID) \(approve ? "allow-once" : "deny")"
        case "qwenpaw": return "/approval \(approve ? "approve" : "deny") \(requestID)"
        case "codex": return "/\(approve ? "approve" : "reject") \(requestID) \(contextID)"
        default: return "/agentteams-approval \(approve ? "approve" : "deny") \(requestID) \(contextID)"
        }
    }
    public func answerContent(approve: Bool, actor: String) -> [String: JSONValue] {
        ["m.relates_to": .object(["m.in_reply_to": .object(["event_id": .string(eventID)])]),
         "com.agentteams.answer": .object(["confirmation_id": .string(requestID), "answer": .string(approve ? "approved" : "rejected"), "actor": .string(actor), "tool_name": .string(tool)])]
    }
}
