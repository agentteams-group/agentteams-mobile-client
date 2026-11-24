package org.agentteams.mobile.core

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

class HITLTest {
    private fun message(content: String, sender: String = "@worker:s") = ChatMessage("request", sender, 1, "Human input", json.parseToJsonElement(content).jsonObject)
    @Test fun newHarnessUsesStructuredApproval() {
        val approval = assertNotNull(Approval.parse(message("""{"org.agentteams.approval":{"version":1,"runtime":"future","reply_format":"com.agentteams.answer","request_id":"one","context_id":"ctx:1","status":"pending","tool_name":"shell"}}"""), "!room:s"))
        assertFalse(approval.command(true).startsWith("/"))
        val answer = approval.answerContent(true, "@human:s").obj("com.agentteams.answer")!!
        assertEquals("tool_approval", answer.text("kind"))
        assertEquals("ctx:1", answer.text("context_id"))
        assertEquals("@worker:s", answer.text("agent_id"))
    }
    @Test fun questionAnswersKeepIDsOptionsAndReceiptOwnership() {
        val input = message("""{"org.agentteams.run":{"version":"1","blocks":[{"type":"confirmation","confirmation_id":"question-1","questions":[{"id":"multi","question":"Choose","multi_select":true,"options":[{"id":"a","label":"A"},{"id":"b","label":"B"}]},{"id":"free","question":"Explain"}]}]}}""")
        val request = QuestionRequest.parse(input, "!room:s").single()
        val result = assertNotNull(request.response(mapOf("multi" to setOf("b", "a")), mapOf("free" to "detail"), "@human:s"))
        assertTrue(result.first.contains("A；B"))
        assertEquals("question-1", result.second.obj("com.agentteams.answer")?.text("confirmation_id"))
        assertNull(request.response(mapOf("multi" to setOf("forged")), mapOf("free" to "detail"), "@human:s"))
        val receipt = """{"org.agentteams.run":{"version":"1","blocks":[{"type":"confirmation","confirmation_id":"question-1","status":"answered"}]}}"""
        assertFalse(request.resolved(listOf(message(receipt, "@other:s"))))
        assertTrue(request.resolved(listOf(message(receipt))))
    }
}
