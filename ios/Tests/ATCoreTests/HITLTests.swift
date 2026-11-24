import Foundation
import Testing
@testable import ATCore

struct HITLTests {
    func message(_ json: String, sender: String = "@worker:s", id: String = "$request") throws -> ChatMessage {
        ChatMessage(id: id, sender: sender, timestamp: 1, body: "Human input", content: try JSONDecoder().decode([String: JSONValue].self, from: Data(json.utf8)))
    }
    @Test func newHarnessUsesStructuredApprovalWithoutClientCommands() throws {
        let input = try message(#"{"org.agentteams.approval":{"version":1,"runtime":"future","reply_format":"com.agentteams.answer","request_id":"one","context_id":"ctx:1","status":"pending","tool_name":"shell"}}"#)
        let approval = try #require(Approval(message: input, roomID: "!room:s"))
        #expect(!approval.command(approve: true).hasPrefix("/"))
        let answer = approval.answerContent(approve: true, actor: "@human:s")["com.agentteams.answer"]?.object
        #expect(answer?["kind"]?.string == "tool_approval")
        #expect(answer?["context_id"]?.string == "ctx:1")
        #expect(answer?["agent_id"]?.string == "@worker:s")
    }
    @Test func questionAnswersKeepIDsOptionsAndReceiptOwnership() throws {
        let input = try message(#"{"org.agentteams.run":{"version":"1","blocks":[{"type":"confirmation","confirmation_id":"question-1","questions":[{"id":"multi","question":"Choose","multi_select":true,"options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]},{"id":"free","question":"Explain"}]}]}}"#)
        let request = try #require(QuestionRequest.parse(input, roomID: "!room:s").first)
        let result = try #require(request.response(selected: ["multi": ["b", "a"]], custom: ["free": "detail"], actor: "@human:s"))
        #expect(result.0.contains("A；B"))
        #expect(result.1["com.agentteams.answer"]?.object?["confirmation_id"]?.string == "question-1")
        #expect(request.response(selected: ["multi": ["forged"]], custom: ["free": "detail"], actor: "@human:s") == nil)
        let receipt = #"{"org.agentteams.run":{"version":"1","blocks":[{"type":"confirmation","confirmation_id":"question-1","status":"answered"}]}}"#
        #expect(!request.resolved(in: [try message(receipt, sender: "@other:s")]))
        #expect(request.resolved(in: [try message(receipt)]))
    }
}
