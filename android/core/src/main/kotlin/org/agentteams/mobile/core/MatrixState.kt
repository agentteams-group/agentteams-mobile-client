package org.agentteams.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class MatrixEvent(
    val type: String,
    val content: JsonObject = JsonObject(emptyMap()),
    val event_id: String = "", val sender: String = "", val origin_server_ts: Long = 0,
    val state_key: String = "", val redacts: String = "",
) {
    val replacementId: String? get() = content.obj("m.relates_to")?.let {
        if (it.text("rel_type") == "m.replace") it.text("event_id") else null
    }
}
@Serializable data class EventBatch(val events: List<MatrixEvent> = emptyList())
@Serializable data class SyncResponse(
    val next_batch: String, val rooms: Rooms = Rooms(), val account_data: EventBatch = EventBatch(),
) {
    @Serializable data class Rooms(
        val join: Map<String, JoinedRoom> = emptyMap(), val invite: Map<String, Invite> = emptyMap(),
        val leave: JsonObject = JsonObject(emptyMap()),
    )
    @Serializable data class Invite(val invite_state: EventBatch = EventBatch())
    @Serializable data class JoinedRoom(
        val state: EventBatch = EventBatch(), val timeline: Timeline = Timeline(), val unread_notifications: Unread? = null,
    )
    @Serializable data class Timeline(val events: List<MatrixEvent> = emptyList(), val prev_batch: String? = null, val limited: Boolean = false)
    @Serializable data class Unread(val notification_count: Int = 0, val highlight_count: Int = 0)
}
@Serializable data class MessagePage(val chunk: List<MatrixEvent> = emptyList(), val end: String? = null, val state: List<MatrixEvent> = emptyList())
data class ChatMessage(val id: String, val sender: String, val timestamp: Long, val body: String, val content: JsonObject)
data class ChatRoom(
    val id: String, val name: String = id, val members: Map<String, String> = emptyMap(),
    val encrypted: Boolean = false, val unread: Int = 0, val highlights: Int = 0,
    val previousBatch: String? = null, val events: Map<String, MatrixEvent> = emptyMap(),
) {
    fun ingest(incoming: List<MatrixEvent>): ChatRoom {
        var title = name
        var encryption = encrypted
        val people = members.toMutableMap()
        val allEvents = events.toMutableMap()
        incoming.forEach { event ->
            when (event.type) {
                "m.room.name" -> title = event.content.text("name") ?: title
                "m.room.encryption", "m.room.encrypted" -> encryption = true
                "m.room.member" -> if (event.content.text("membership") == "join") {
                    people[event.state_key] = event.content.text("displayname") ?: event.state_key
                } else people.remove(event.state_key)
            }
            if (event.event_id.isNotEmpty()) allEvents[event.event_id] = event
        }
        return copy(name = title, encrypted = encryption, members = people, events = allEvents)
    }
    val messages: List<ChatMessage> get() {
        val redacted = events.values.filter { it.type == "m.room.redaction" }
            .map { it.redacts.ifEmpty { it.content.text("redacts") ?: "" } }.toSet()
        val edits = events.values.filter { it.replacementId != null && it.event_id !in redacted }.groupBy { it.replacementId }
        return events.values.filter { it.type == "m.room.message" && it.replacementId == null && it.event_id !in redacted }
            .map { event ->
                val edited = edits[event.event_id]?.filter { it.sender == event.sender }?.maxByOrNull { it.origin_server_ts }
                val content = edited?.content?.obj("m.new_content") ?: event.content
                ChatMessage(event.event_id, event.sender, event.origin_server_ts, content.text("body") ?: "", content)
            }.sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
    }
}
data class MatrixState(
    val rooms: Map<String, ChatRoom> = emptyMap(), val invites: Map<String, String> = emptyMap(),
    val directRooms: Map<String, List<String>> = emptyMap(), val nextBatch: String? = null,
) {
    fun apply(response: SyncResponse): MatrixState {
        var direct = directRooms
        response.account_data.events.filter { it.type == "m.direct" }.forEach { event ->
            direct = event.content.mapValues { (_, value) -> (value as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
        }
        val nextRooms = rooms.toMutableMap()
        val nextInvites = invites.toMutableMap()
        response.rooms.invite.forEach { (id, invite) ->
            nextInvites[id] = invite.invite_state.events.firstOrNull { it.type == "m.room.name" }?.content?.text("name") ?: id
        }
        response.rooms.join.forEach { (id, joined) ->
            var room = nextRooms[id] ?: ChatRoom(id)
            room = if (joined.timeline.limited) room.copy(events = emptyMap(), previousBatch = joined.timeline.prev_batch)
                else room.copy(previousBatch = room.previousBatch ?: joined.timeline.prev_batch)
            room = room.ingest(joined.state.events).ingest(joined.timeline.events)
            joined.unread_notifications?.let { room = room.copy(unread = it.notification_count, highlights = it.highlight_count) }
            nextRooms[id] = room; nextInvites.remove(id)
        }
        response.rooms.leave.keys.forEach { nextRooms.remove(it); nextInvites.remove(it) }
        return MatrixState(nextRooms, nextInvites, direct, response.next_batch)
    }
    fun updateRoom(id: String, update: (ChatRoom) -> ChatRoom): MatrixState =
        rooms[id]?.let { copy(rooms = rooms + (id to update(it))) } ?: this
}
