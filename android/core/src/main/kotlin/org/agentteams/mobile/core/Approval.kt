package org.agentteams.mobile.core

import kotlinx.serialization.json.*

data class Approval(
    val roomId: String, val eventId: String, val sender: String,
    val runtime: String, val requestId: String, val contextId: String,
    val tool: String, val parameters: String, val expiresAt: Long?,
    val structuredReply: Boolean = false,
) {
    val id: String get() = "$roomId/$sender/$runtime/$contextId/$requestId"
    fun expired(now: Long = System.currentTimeMillis()): Boolean = expiresAt?.let { it <= now } ?: false
    fun command(approve: Boolean): String = if (structuredReply) "${if (approve) "批准" else "拒绝"}请求 $requestId" else when (runtime) {
        "openclaw" -> "/approve $requestId ${if (approve) "allow-once" else "deny"}"
        "qwenpaw" -> "/approval ${if (approve) "approve" else "deny"} $requestId"
        "codex" -> "/${if (approve) "approve" else "reject"} $requestId $contextId"
        else -> "/agentteams-approval ${if (approve) "approve" else "deny"} $requestId $contextId"
    }
    fun answerContent(approve: Boolean, actor: String): JsonObject = buildJsonObject {
        putJsonObject("m.relates_to") { putJsonObject("m.in_reply_to") { put("event_id", eventId) } }
        putJsonObject("com.agentteams.answer") {
            put("confirmation_id", requestId); put("answer", if (approve) "approved" else "rejected")
            put("actor", actor); put("tool_name", tool)
            if (structuredReply) {
                put("kind", "tool_approval"); put("context_id", contextId); put("agent_id", sender)
            }
        }
    }
    companion object {
        fun parse(message: ChatMessage, roomId: String): Approval? {
            val data = message.content.obj("org.agentteams.approval") ?: return null
            if ((data["version"] as? JsonPrimitive)?.intOrNull != 1 || data.text("status") != "pending") return null
            val runtime = data.text("runtime") ?: return null
            val structured = data.text("reply_format") == "com.agentteams.answer"
            if (!structured && runtime !in listOf("openclaw", "qwenpaw", "hermes", "deepseek-harness", "openhuman", "codex")) return null
            val request = data.text("request_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]+")) } ?: return null
            val context = data.text("context_id") ?: ""
            if (structured && !context.matches(Regex("[A-Za-z0-9_.:-]+"))) return null
            if (!structured && runtime in listOf("hermes", "deepseek-harness", "openhuman", "codex") &&
                !context.matches(Regex(if (runtime == "codex") "[A-Za-z0-9_.:-]+" else "[A-Za-z0-9_.-]+"))) return null
            val tool = data.text("tool_name")?.takeIf { it.isNotEmpty() } ?: return null
            return Approval(roomId, message.id, message.sender, runtime, request, context, tool,
                data.text("parameters") ?: "", (data["expires_at"] as? JsonPrimitive)?.longOrNull,
                data.text("reply_format") == "com.agentteams.answer")
        }
    }
}

fun pendingApprovals(state: MatrixState, assistants: List<Assistant>, currentUser: String, submitted: Set<String>): List<Approval> {
    val knownSenders = assistants.map { it.matrixUserID }.toSet() - ""
    return state.rooms.values.flatMap { room ->
        val messages = room.messages
        messages.mapNotNull { message ->
            if (message.sender == currentUser || message.sender !in knownSenders) return@mapNotNull null
            val approval = Approval.parse(message, room.id) ?: return@mapNotNull null
            if (approval.expired() || approval.id in submitted) return@mapNotNull null
            val resolved = messages.any { other ->
                val data = other.content.obj("org.agentteams.approval")
                data != null && other.sender == approval.sender && data.text("request_id") == approval.requestId &&
                    data.text("runtime") == approval.runtime && data.text("context_id").orEmpty() == approval.contextId &&
                    data.text("status") in listOf("approved", "rejected", "cancelled", "expired")
            }
            val answered = messages.any { other ->
                other.sender == currentUser && other.content.obj("m.relates_to")?.obj("m.in_reply_to")?.text("event_id") == message.id &&
                    other.content.obj("com.agentteams.answer")?.text("confirmation_id") == approval.requestId
            }
            if (resolved || answered) null else approval
        }
    }.distinctBy { it.id }
}
