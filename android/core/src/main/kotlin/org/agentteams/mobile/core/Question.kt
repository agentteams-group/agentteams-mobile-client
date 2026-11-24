package org.agentteams.mobile.core

import kotlinx.serialization.json.*

data class HumanQuestion(val id: String, val text: String, val options: List<Pair<String, String>>, val multiSelect: Boolean)
data class QuestionRequest(val requestId: String, val roomId: String, val eventId: String, val sender: String,
                           val questions: List<HumanQuestion>, val expiresAt: Long?) {
    val id: String get() = "$roomId/$sender/$requestId"
    fun expired(now: Long = System.currentTimeMillis()): Boolean = expiresAt?.let { it <= now } ?: false
    fun resolved(messages: List<ChatMessage>): Boolean = messages.any { message ->
        message.sender == sender && (message.content.obj("org.agentteams.run")?.get("blocks") as? JsonArray)?.any {
            val block = it as? JsonObject
            block?.text("confirmation_id") == requestId && block.text("status") in listOf("answered", "cancelled", "expired")
        } == true
    }
    fun response(selected: Map<String, Set<String>>, custom: Map<String, String>, actor: String): Pair<String, JsonObject>? {
        if (expired()) return null
        val lines = mutableListOf<String>()
        val responses = questions.map { q ->
            val choices = selected[q.id].orEmpty()
            val text = custom[q.id].orEmpty().trim()
            if ((!q.multiSelect && choices.size > 1) || choices.any { choice -> q.options.none { it.first == choice } } ||
                (choices.isEmpty() && text.isEmpty())) return null
            val ordered = q.options.filter { it.first in choices }
            lines += (ordered.map { it.second } + listOf(text).filter { it.isNotEmpty() }).joinToString("；")
            buildJsonObject {
                put("id", q.id); put("selected", JsonArray(ordered.map { JsonPrimitive(it.first) })); put("custom", text)
            }
        }
        val body = "【干预答复】\n" + lines.joinToString("\n")
        return body to buildJsonObject {
            putJsonObject("com.agentteams.answer") {
                put("kind", "ask_user"); put("confirmation_id", requestId); put("agent_id", sender)
                put("actor", actor); put("answer", body); put("responses", JsonArray(responses))
            }
            putJsonObject("m.relates_to") { putJsonObject("m.in_reply_to") { put("event_id", eventId) } }
        }
    }
    companion object {
        fun parse(message: ChatMessage, roomId: String): List<QuestionRequest> {
            val run = message.content.obj("org.agentteams.run") ?: return emptyList()
            if (run.text("version") != "1") return emptyList()
            return (run["blocks"] as? JsonArray).orEmpty().mapNotNull { raw ->
                val block = raw as? JsonObject ?: return@mapNotNull null
                if (block.text("type") != "confirmation" || block.text("status") in listOf("answered", "cancelled", "expired")) return@mapNotNull null
                val request = block.text("confirmation_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val source = (block["questions"] as? JsonArray)?.toList() ?: listOf(block)
                val questions = source.mapIndexedNotNull { index, item ->
                    val q = item as? JsonObject ?: return@mapIndexedNotNull null
                    val text = q.text("question")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    val options = (q["options"] as? JsonArray).orEmpty().mapNotNull { option ->
                        val value = option as? JsonObject ?: return@mapNotNull null
                        val id = value.text("id") ?: return@mapNotNull null
                        val label = value.text("label") ?: return@mapNotNull null
                        id to label
                    }
                    HumanQuestion(q.text("id") ?: index.toString(), text, options, (q["multi_select"] as? JsonPrimitive)?.booleanOrNull == true)
                }
                if (questions.isEmpty() || questions.size != source.size || questions.map { it.id }.distinct().size != questions.size) return@mapNotNull null
                QuestionRequest(request, roomId, message.id, message.sender, questions, (block["expires_at"] as? JsonPrimitive)?.longOrNull)
            }
        }
    }
}
