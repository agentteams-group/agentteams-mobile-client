package org.agentteams.mobile.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ApiTest {
    private val server = MockWebServer().apply { start() }
    private val api = ApiClient()
    private fun session() = AccountSession(server.url("/matrix/").toString(), server.url("/controller/").toString(), "test-token", "@alice:s")
    private fun enqueue(body: String, code: Int = 200) { server.enqueue(MockResponse().setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")) }
    @After fun close() { server.shutdown() }

    @Test fun loginCarriesHumanTokenToControllerAndDoesNotStorePassword() = runBlocking {
        enqueue("""{"access_token":"test-token","user_id":"@alice:s"}""")
        enqueue("""{"name":"alice","permissionLevel":2}""")
        val s = session()
        val loggedIn = api.login(s.matrix, s.controller, " alice ", " secret ")
        val login = server.takeRequest()
        assertEquals("/matrix/_matrix/client/v3/login", login.path)
        assertNull(login.getHeader("Authorization"))
        val content = json.parseToJsonElement(login.body.readUtf8()) as JsonObject
        assertEquals(" secret ", content.text("password"))
        assertEquals("alice", content.obj("identifier")?.text("user"))
        val me = server.takeRequest()
        assertEquals("/controller/api/v1/me", me.path)
        assertEquals("Bearer test-token", me.getHeader("Authorization"))
        assertFalse(json.encodeToString(loggedIn).contains("secret"))
    }

    @Test fun failedControllerAuthorizationRevokesNewToken() = runBlocking {
        enqueue("""{"access_token":"test-token","user_id":"@alice:s"}""")
        enqueue("""{"error":"Human not registered"}""", 403)
        enqueue("{}")
        val s = session()
        val error = assertFailsWith<ApiException> { api.login(s.matrix, s.controller, "alice", "password") }
        assertEquals(403, error.status)
        server.takeRequest(); server.takeRequest()
        assertEquals("/matrix/_matrix/client/v3/logout", server.takeRequest().path)
    }

    @Test fun messageRetryKeepsTransactionAndMention() = runBlocking {
        enqueue("""{"event_id":"message"}""")
        assertEquals("message", api.send(session(), "!r/a:s", "@worker 继续", "same-transaction", "@worker:s"))
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/matrix/_matrix/client/v3/rooms/!r%2Fa:s/send/m.room.message/same-transaction", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        val content = json.parseToJsonElement(request.body.readUtf8()) as JsonObject
        assertEquals("@worker 继续", content.text("body"))
        assertEquals("@worker:s", content.obj("m.mentions")?.get("user_ids")?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test fun workflowCarriesTeamAndRawTaskDetails() = runBlocking {
        enqueue("""{"project_id":"same","title":"项目","status":"active","nodes":[],"tasks_detail":[]}""")
        api.workflow(session(), Project("same", "项目", "active", "team/a"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("team/a", url.queryParameter("team"))
        assertEquals("true", url.queryParameter("includeTasks"))
    }

    @Test fun syncEncodesCursorWithoutLeakingToken() = runBlocking {
        enqueue("""{"next_batch":"next"}""")
        assertEquals("next", api.sync(session(), "a+b&/").next_batch)
        val request = server.takeRequest()
        assertFalse(request.path!!.contains("test-token"))
        assertEquals("a+b&/", request.requestUrl?.queryParameter("since"))
        assertEquals("30000", request.requestUrl?.queryParameter("timeout"))
    }

    @Test fun serverFailuresDoNotTurnIntoEmptyTaskLists() = runBlocking {
        enqueue("""{"error":"storage unavailable"}""", 503)
        val error = assertFailsWith<ApiException> { api.projects(session()) }
        assertEquals(503, error.status)
        assertTrue(error.message.orEmpty().contains("storage unavailable"))
    }

    @Test fun redirectsDoNotForwardCredentials() = runBlocking {
        MockWebServer().use { destination ->
            destination.start()
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", destination.url("/steal")))
            assertFailsWith<ApiException> { api.me(session()) }
            assertNull(destination.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun cancelSyncCancelsNetworkRequest() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertFailsWith<TimeoutCancellationException> { withTimeout(300) { api.sync(session(), "cursor") } }
        Unit
    }

    @Test fun approvalCommandsTargetSixRuntimesAndRejectMalformedIds() {
        val commands = mapOf(
            "openclaw" to "/approve req-1 allow-once",
            "qwenpaw" to "/approval approve req-1",
            "hermes" to "/agentteams-approval approve req-1 ctx-1",
            "deepseek-harness" to "/agentteams-approval approve req-1 ctx-1",
            "openhuman" to "/agentteams-approval approve req-1 ctx-1",
            "codex" to "/approve req-1 ctx-1",
        )
        commands.forEach { (runtime, command) ->
            val content = buildJsonObject { putJsonObject("org.agentteams.approval") {
                put("version", 1); put("runtime", runtime); put("status", "pending")
                put("request_id", "req-1"); put("context_id", "ctx-1"); put("tool_name", "shell")
            } }
            val approval = assertNotNull(Approval.parse(ChatMessage("event", "@bot:s", 0, "approve?", content), "!r:s"))
            assertEquals(command, approval.command(true))
            assertEquals("event", approval.answerContent(true, "@alice:s").obj("m.relates_to")?.obj("m.in_reply_to")?.text("event_id"))
        }
        val bad = buildJsonObject { putJsonObject("org.agentteams.approval") {
            put("version", 1); put("runtime", "qwenpaw"); put("status", "pending")
            put("request_id", "one\n/approve two"); put("tool_name", "shell")
        } }
        assertNull(Approval.parse(ChatMessage("bad", "@bot:s", 0, "", bad), "!r:s"))
        val numericId = buildJsonObject { putJsonObject("org.agentteams.approval") {
            put("version", 1); put("runtime", "qwenpaw"); put("status", "pending")
            put("request_id", 1); put("tool_name", "shell")
        } }
        assertNull(Approval.parse(ChatMessage("numeric", "@bot:s", 0, "", numericId), "!r:s"))
    }

    @Test fun answeredApprovalDoesNotHideAnotherRequest() {
        fun event(id: String, request: String, status: String) = MatrixEvent("m.room.message", buildJsonObject {
            putJsonObject("org.agentteams.approval") {
                put("version", 1); put("runtime", "qwenpaw"); put("status", status); put("request_id", request); put("tool_name", "shell")
            }
        }, id, "@bot:s")
        val room = ChatRoom("!r:s").ingest(listOf(event("one", "one", "pending"), event("two", "two", "pending"), event("done", "one", "approved")))
        val state = MatrixState(rooms = mapOf(room.id to room))
        assertEquals(listOf("two"), pendingApprovals(state, listOf(Assistant("bot", matrixUserID = "@bot:s")), "@alice:s", emptySet()).map { it.requestId })
        assertTrue(pendingApprovals(state, emptyList(), "@alice:s", emptySet()).isEmpty())
    }
}
