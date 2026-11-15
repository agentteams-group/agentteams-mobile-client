import Foundation
import Testing
@testable import ATCore

struct ContractTests {
    @Test func endpointValidationAndEncodedRoomPath() throws {
        #expect(throws: APIError.self) { try ServerAddress("https://user:password@example.com") }
        #expect(throws: APIError.self) { try ServerAddress("https://example.com?token=secret") }
        #expect(throws: APIError.self) { try ServerAddress("file:///tmp/server") }
        let address = try ServerAddress(" https://example.com/at/ ")
        let url = address.url(["rooms", "!room/a:#?:server", "messages"], query: ["from": "a+b&c"])
        #expect(url.pathComponents.last == "messages")
        #expect(url.absoluteString.contains("%2Fa%3A%23%3F%3Aserver"))
        #expect(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first?.value == "a+b&c")
    }

    @Test func taskUsesRawSubmissionStatusAndTeamScopedIdentity() throws {
        let json = #"{"project_id":"p","title":"项目","status":"active","team_id":"blue","nodes":[{"id":"t","name":"写报告","status":"completed"}],"tasks_detail":[{"task_id":"t","status":"submitted","room_id":"!task:server","summary":"请验收"}],"source_room_id":"!project:server"}"#
        let workflow = try JSONDecoder().decode(Workflow.self, from: Data(json.utf8))
        let task = try #require(workflow.boardTasks.first)
        #expect(task.stage == .review)
        #expect(task.roomID == "!task:server")
        #expect(task.id == "blue/p/t")
        #expect(task.summary == "请验收")
    }

    @Test func syncPreservesRoomsAndAppliesEditsRedactionsAndLeave() throws {
        var state = MatrixState()
        try apply(#"{"next_batch":"1","rooms":{"join":{"!r:s":{"state":{"events":[{"type":"m.room.name","content":{"name":"协作"}}]},"timeline":{"events":[{"event_id":"$one","sender":"@bot:s","type":"m.room.message","origin_server_ts":1,"content":{"body":"初稿","msgtype":"m.text"}}],"prev_batch":"old"}}}}}"#, to: &state)
        try apply(#"{"next_batch":"2","rooms":{"join":{"!r:s":{"timeline":{"events":[{"event_id":"$edit","sender":"@bot:s","type":"m.room.message","origin_server_ts":2,"content":{"body":"* 新稿","m.relates_to":{"rel_type":"m.replace","event_id":"$one"},"m.new_content":{"body":"新稿","msgtype":"m.text"}}}]}}}}}"#, to: &state)
        #expect(state.rooms["!r:s"]?.name == "协作")
        #expect(state.rooms["!r:s"]?.messages.map(\.body) == ["新稿"])
        #expect(state.rooms["!r:s"]?.previousBatch == "old")
        try apply(#"{"next_batch":"3","rooms":{"join":{"!r:s":{"timeline":{"events":[{"event_id":"$redact","type":"m.room.redaction","redacts":"$one","content":{}}]}}}}}"#, to: &state)
        #expect(state.rooms["!r:s"]?.messages.isEmpty == true)
        try apply(#"{"next_batch":"4","rooms":{"leave":{"!r:s":{}}}}"#, to: &state)
        #expect(state.rooms["!r:s"] == nil)
    }

    @Test func limitedTimelineDoesNotSilentlyBridgeGapAndEncryptionBlocksComposer() throws {
        var state = MatrixState()
        try apply(#"{"next_batch":"1","rooms":{"join":{"!r:s":{"timeline":{"events":[{"event_id":"$old","type":"m.room.message","content":{"body":"old"}}],"prev_batch":"before"}}}}}"#, to: &state)
        try apply(#"{"next_batch":"2","rooms":{"join":{"!r:s":{"state":{"events":[{"type":"m.room.encryption","content":{"algorithm":"m.megolm.v1.aes-sha2"}}]},"timeline":{"limited":true,"prev_batch":"gap","events":[{"event_id":"$new","type":"m.room.message","content":{"body":"new"}}]}}}}}"#, to: &state)
        #expect(state.rooms["!r:s"]?.messages.map(\.body) == ["new"])
        #expect(state.rooms["!r:s"]?.previousBatch == "gap")
        #expect(state.rooms["!r:s"]?.encrypted == true)
    }

    @Test func invitesDirectChatsAndUnreadCounts() throws {
        var state = MatrixState()
        try apply(#"{"next_batch":"1","account_data":{"events":[{"type":"m.direct","content":{"@manager:s":["!dm:s"]}}]},"rooms":{"invite":{"!invite:s":{"invite_state":{"events":[{"type":"m.room.name","content":{"name":"邀请你协作"}}]}}},"join":{"!dm:s":{"unread_notifications":{"notification_count":3,"highlight_count":1},"timeline":{"events":[]}}}}}"#, to: &state)
        #expect(state.directRooms["@manager:s"] == ["!dm:s"])
        #expect(state.invites["!invite:s"] == "邀请你协作")
        #expect(state.rooms["!dm:s"]?.highlights == 1)
    }

    @Test func foreignEditCannotReplaceAnotherSendersMessage() throws {
        var state = MatrixState()
        try apply(#"{"next_batch":"1","rooms":{"join":{"!r:s":{"timeline":{"events":[{"event_id":"$one","sender":"@human:s","type":"m.room.message","content":{"body":"original"}},{"event_id":"$fake","sender":"@bot:s","type":"m.room.message","content":{"m.relates_to":{"rel_type":"m.replace","event_id":"$one"},"m.new_content":{"body":"forged"}}}]}}}}}"#, to: &state)
        #expect(state.rooms["!r:s"]?.messages.first?.body == "original")
    }

    private func apply(_ json: String, to state: inout MatrixState) throws {
        state.apply(try JSONDecoder().decode(SyncResponse.self, from: Data(json.utf8)))
    }
}
