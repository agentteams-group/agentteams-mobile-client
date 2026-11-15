package org.agentteams.mobile.core

import kotlinx.serialization.decodeFromString
import org.junit.Test
import kotlin.test.*

class ContractTest {
    @Test fun addressValidationAndEncoding() {
        listOf("file:///tmp/server", "https://user:secret@example.com", "https://example.com?token=secret", "https://example.com/#secret").forEach {
            assertFailsWith<IllegalArgumentException> { ServerAddress(it) }
        }
        val url = ServerAddress(" https://example.com/prefix/ ").url(listOf("rooms", "!r/a:#?:server", "messages"), mapOf("from" to "a+b&c"))
        assertTrue(url.encodedPath.contains("!r%2Fa:%23%3F:server"))
        assertEquals("a+b&c", url.queryParameter("from"))
        assertEquals("/prefix/rooms/!r/a:#?:server/messages", url.pathSegments.joinToString("/", prefix = "/"))
    }

    @Test fun rawSubmittedStatusAndTeamIdentity() {
        val workflow = json.decodeFromString<Workflow>("""{"project_id":"p","title":"项目","status":"active","team_id":"blue","nodes":[{"id":"t","name":"写报告","status":"completed"}],"tasks_detail":[{"task_id":"t","status":"submitted","room_id":"!task:s","summary":"请验收"}],"source_room_id":"!project:s"}""")
        val task = workflow.boardTasks.single()
        assertEquals(TaskStage.REVIEW, task.stage)
        assertEquals("blue/p/t", task.id)
        assertEquals("!task:s", task.roomId)
        assertEquals("请验收", task.summary)
    }

    @Test fun nullableCollectionsAreEmptyNotCrashes() {
        val workflow = json.decodeFromString<Workflow>("""{"project_id":"p","title":"项目","status":"active","nodes":null,"tasks_detail":null}""")
        assertTrue(workflow.boardTasks.isEmpty())
    }

    @Test fun syncMergesEditsAndRedactionsThenRemovesLeftRoom() {
        var state = sync("""{"next_batch":"1","rooms":{"join":{"!r:s":{"state":{"events":[{"type":"m.room.name","content":{"name":"协作"}}]},"timeline":{"events":[{"event_id":"one","sender":"@bot:s","type":"m.room.message","origin_server_ts":1,"content":{"body":"初稿"}}],"prev_batch":"old"}}}}}""")
        state = sync("""{"next_batch":"2","rooms":{"join":{"!r:s":{"timeline":{"events":[{"event_id":"edit","sender":"@bot:s","type":"m.room.message","origin_server_ts":2,"content":{"m.relates_to":{"rel_type":"m.replace","event_id":"one"},"m.new_content":{"body":"新稿"}}}]}}}}}""", state)
        assertEquals("协作", state.rooms["!r:s"]?.name)
        assertEquals(listOf("新稿"), state.rooms["!r:s"]?.messages?.map { it.body })
        assertEquals("old", state.rooms["!r:s"]?.previousBatch)
        state = sync("""{"next_batch":"3","rooms":{"join":{"!r:s":{"timeline":{"events":[{"event_id":"redact","type":"m.room.redaction","redacts":"one","content":{}}]}}}}}""", state)
        assertTrue(state.rooms.getValue("!r:s").messages.isEmpty())
        assertTrue(sync("""{"next_batch":"4","rooms":{"leave":{"!r:s":{}}}}""", state).rooms.isEmpty())
    }

    @Test fun foreignSenderCannotEditMessageAndLimitedSyncResetsGap() {
        var state = sync("""{"next_batch":"1","rooms":{"join":{"!r:s":{"timeline":{"events":[{"event_id":"one","sender":"@human:s","type":"m.room.message","content":{"body":"original"}},{"event_id":"fake","sender":"@bot:s","type":"m.room.message","content":{"m.relates_to":{"rel_type":"m.replace","event_id":"one"},"m.new_content":{"body":"forged"}}}]}}}}}""")
        assertEquals("original", state.rooms.getValue("!r:s").messages.single().body)
        state = sync("""{"next_batch":"2","rooms":{"join":{"!r:s":{"state":{"events":[{"type":"m.room.encryption","content":{}}]},"timeline":{"limited":true,"prev_batch":"gap","events":[{"event_id":"new","type":"m.room.message","content":{"body":"new"}}]}}}}}""", state)
        assertEquals(listOf("new"), state.rooms.getValue("!r:s").messages.map { it.body })
        assertEquals("gap", state.rooms.getValue("!r:s").previousBatch)
        assertTrue(state.rooms.getValue("!r:s").encrypted)
    }

    @Test fun invitesDirectRoomsAndUnreadCounts() {
        val state = sync("""{"next_batch":"1","account_data":{"events":[{"type":"m.direct","content":{"@manager:s":["!dm:s"]}}]},"rooms":{"invite":{"!invite:s":{"invite_state":{"events":[{"type":"m.room.name","content":{"name":"协作邀请"}}]}}},"join":{"!dm:s":{"unread_notifications":{"notification_count":3,"highlight_count":1},"timeline":{"events":[]}}}}}""")
        assertEquals(listOf("!dm:s"), state.directRooms["@manager:s"])
        assertEquals("协作邀请", state.invites["!invite:s"])
        assertEquals(1, state.rooms["!dm:s"]?.highlights)
    }

    private fun sync(raw: String, state: MatrixState = MatrixState()) = state.apply(json.decodeFromString<SyncResponse>(raw))
}
