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
    public let structuredReply: Bool

    public init?(message: ChatMessage, roomID: String) {
        guard let data = message.content["org.agentteams.approval"]?.object,
              data["version"] == .number(1), data["status"]?.string == "pending",
              let runtime = data["runtime"]?.string,
              (data["reply_format"]?.string == "com.agentteams.answer" || ["openclaw", "qwenpaw", "hermes", "deepseek-harness", "openhuman", "codex"].contains(runtime)),
              let requestID = data["request_id"]?.string, Self.valid(requestID, colon: true),
              let tool = data["tool_name"]?.string, !tool.isEmpty else { return nil }
        let context = data["context_id"]?.string ?? ""
        if data["reply_format"]?.string == "com.agentteams.answer", !Self.valid(context, colon: true) { return nil }
        if data["reply_format"]?.string != "com.agentteams.answer", ["hermes", "deepseek-harness", "openhuman", "codex"].contains(runtime),
           !Self.valid(context, colon: runtime == "codex") { return nil }
        self.id = "\(roomID)/\(message.sender)/\(runtime)/\(context)/\(requestID)"
        self.eventID = message.id; self.roomID = roomID; sender = message.sender
        self.tool = tool; parameters = data["parameters"]?.string ?? ""
        self.runtime = runtime; self.requestID = requestID; contextID = context
        structuredReply = data["reply_format"]?.string == "com.agentteams.answer"
        if case .number(let expires) = data["expires_at"] { expiresAt = expires } else { expiresAt = nil }
    }

    private static func valid(_ value: String, colon: Bool) -> Bool {
        !value.isEmpty && value.range(of: colon ? "^[A-Za-z0-9_.:-]+$" : "^[A-Za-z0-9_.-]+$", options: .regularExpression) != nil
    }
    public var expired: Bool { expiresAt.map { $0 <= Date().timeIntervalSince1970 * 1000 } ?? false }
    public func command(approve: Bool) -> String {
        if structuredReply { return "\(approve ? "批准" : "拒绝")请求 \(requestID)" }
        switch runtime {
        case "openclaw": return "/approve \(requestID) \(approve ? "allow-once" : "deny")"
        case "qwenpaw": return "/approval \(approve ? "approve" : "deny") \(requestID)"
        case "codex": return "/\(approve ? "approve" : "reject") \(requestID) \(contextID)"
        default: return "/agentteams-approval \(approve ? "approve" : "deny") \(requestID) \(contextID)"
        }
    }
    public func answerContent(approve: Bool, actor: String) -> [String: JSONValue] {
        var answer: [String: JSONValue] = ["confirmation_id": .string(requestID), "answer": .string(approve ? "approved" : "rejected"), "actor": .string(actor), "tool_name": .string(tool)]
        if structuredReply {
            answer["kind"] = .string("tool_approval"); answer["context_id"] = .string(contextID); answer["agent_id"] = .string(sender)
        }
        return ["m.relates_to": .object(["m.in_reply_to": .object(["event_id": .string(eventID)])]),
                "com.agentteams.answer": .object(answer)]
    }
}
