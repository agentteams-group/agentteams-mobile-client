import Foundation

public struct HumanQuestion: Identifiable, Sendable {
    public struct Option: Identifiable, Sendable { public let id: String; public let label: String }
    public let id: String
    public let text: String
    public let options: [Option]
    public let multiSelect: Bool
}

public struct QuestionRequest: Identifiable, Sendable {
    public let requestID: String
    public let roomID: String
    public let eventID: String
    public let sender: String
    public let questions: [HumanQuestion]
    public let expiresAt: Double?
    public var id: String { "\(roomID)/\(sender)/\(requestID)" }
    public var expired: Bool { expiresAt.map { $0 <= Date().timeIntervalSince1970 * 1000 } ?? false }

    public static func parse(_ message: ChatMessage, roomID: String) -> [QuestionRequest] {
        guard let run = message.content["org.agentteams.run"]?.object, run["version"]?.string == "1",
              case .array(let blocks) = run["blocks"] else { return [] }
        return blocks.compactMap { raw in
            guard let block = raw.object, block["type"]?.string == "confirmation",
                  !["answered", "cancelled", "expired"].contains(block["status"]?.string ?? ""),
                  let request = block["confirmation_id"]?.string, !request.isEmpty else { return nil }
            let source: [JSONValue]
            if case .array(let items) = block["questions"] { source = items } else { source = [.object(block)] }
            let questions: [HumanQuestion] = source.enumerated().compactMap { index, item in
                guard let q = item.object, let text = q["question"]?.string, !text.isEmpty else { return nil }
                var options: [HumanQuestion.Option] = []
                if case .array(let entries) = q["options"] {
                    options = entries.compactMap { entry in
                        guard let value = entry.object, let id = value["id"]?.string, !id.isEmpty,
                              let label = value["label"]?.string, !label.isEmpty else { return nil }
                        return HumanQuestion.Option(id: id, label: label)
                    }
                }
                return HumanQuestion(id: q["id"]?.string ?? String(index), text: text, options: options, multiSelect: q["multi_select"] == .bool(true))
            }
            guard questions.count == source.count, !questions.isEmpty,
                  Set(questions.map(\.id)).count == questions.count else { return nil }
            var expires: Double?
            if case .number(let value) = block["expires_at"] { expires = value }
            return QuestionRequest(requestID: request, roomID: roomID, eventID: message.id, sender: message.sender, questions: questions, expiresAt: expires)
        }
    }

    public func resolved(in messages: [ChatMessage]) -> Bool {
        messages.contains { message in
            guard message.sender == sender, let run = message.content["org.agentteams.run"]?.object,
                  case .array(let blocks) = run["blocks"] else { return false }
            return blocks.contains { block in
                block.object?["confirmation_id"]?.string == requestID &&
                ["answered", "cancelled", "expired"].contains(block.object?["status"]?.string ?? "")
            }
        }
    }

    public func response(selected: [String: Set<String>], custom: [String: String], actor: String) -> (String, [String: JSONValue])? {
        guard !expired else { return nil }
        var responses: [JSONValue] = []
        var lines: [String] = []
        for q in questions {
            let choices = selected[q.id] ?? []
            let text = (custom[q.id] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard (q.multiSelect || choices.count <= 1), choices.isSubset(of: Set(q.options.map(\.id))),
                  !choices.isEmpty || !text.isEmpty else { return nil }
            let ordered = q.options.filter { choices.contains($0.id) }
            responses.append(.object(["id": .string(q.id), "selected": .array(ordered.map { .string($0.id) }), "custom": .string(text)]))
            lines.append((ordered.map(\.label) + (text.isEmpty ? [] : [text])).joined(separator: "；"))
        }
        let body = "【干预答复】\n" + lines.joined(separator: "\n")
        return (body, ["com.agentteams.answer": .object(["kind": .string("ask_user"), "confirmation_id": .string(requestID),
            "agent_id": .string(sender), "actor": .string(actor), "answer": .string(body), "responses": .array(responses)]),
            "m.relates_to": .object(["m.in_reply_to": .object(["event_id": .string(eventID)])])])
    }
}
