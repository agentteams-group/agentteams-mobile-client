import Foundation
import Testing
@testable import ATCore

@Suite(.serialized)
struct APITests {
    private func client(_ handler: @escaping (URLRequest) throws -> (Int, String)) -> APIClient {
        StubProtocol.handler = handler
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return APIClient(transport: URLSession(configuration: config))
    }
    private func session() throws -> AccountSession {
        AccountSession(matrix: try ServerAddress("https://matrix.example.com"),
                       controller: try ServerAddress("https://controller.example.com/prefix"),
                       accessToken: "test-token", userID: "@alice:example.com")
    }

    @Test func loginUsesMatrixIdentityForControllerAndDoesNotPersistPassword() async throws {
        var paths: [String] = []
        let api = client { request in
            paths.append(request.url!.path)
            if request.url!.path.hasSuffix("login") {
                #expect(request.httpMethod == "POST")
                #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
                let body = try JSONDecoder().decode([String: JSONValue].self, from: request.bodyData)
                #expect(body["identifier"]?.object?["user"]?.string == "alice")
                #expect(body["password"]?.string == " secret ")
                return (200, #"{"access_token":"test-token","user_id":"@alice:example.com"}"#)
            }
            #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer test-token")
            return (200, #"{"name":"alice","permissionLevel":2}"#)
        }
        let s = try session()
        let loggedIn = try await api.login(matrix: s.matrix, controller: s.controller, username: "alice", password: " secret ")
        #expect(paths == ["/_matrix/client/v3/login", "/prefix/api/v1/me"])
        #expect(loggedIn.userID == "@alice:example.com")
        #expect(!String(decoding: try JSONEncoder().encode(loggedIn), as: UTF8.self).contains("secret"))
    }

    @Test func failedControllerAuthorizationRevokesNewMatrixToken() async throws {
        var revoked = false
        let api = client { request in
            switch request.url!.lastPathComponent {
            case "login": return (200, #"{"access_token":"test-token","user_id":"@alice:example.com"}"#)
            case "logout": revoked = true; return (200, "{}")
            default: return (403, #"{"error":"not a registered Human"}"#)
            }
        }
        let s = try session()
        do {
            _ = try await api.login(matrix: s.matrix, controller: s.controller, username: "alice", password: "password")
            Issue.record("Expected Controller permission rejection")
        } catch let error as APIError {
            if case .http(let status, _) = error { #expect(status == 403) } else { Issue.record("Wrong error") }
        }
        #expect(revoked)
    }

    @Test func sendPreservesTransactionIDAndStructuredMention() async throws {
        let api = client { request in
            #expect(request.httpMethod == "PUT")
            #expect(request.url!.lastPathComponent == "retry-same-id")
            #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer test-token")
            let body = try JSONDecoder().decode([String: JSONValue].self, from: request.bodyData)
            #expect(body["m.mentions"]?.object?["user_ids"]?.strings == ["@worker:example.com"])
            #expect(body["body"]?.string == "@worker 继续")
            return (200, #"{"event_id":"$message"}"#)
        }
        let result = try await api.send(session(), room: "!room/a:example.com", body: "@worker 继续",
                                        transactionID: "retry-same-id", mention: "@worker:example.com")
        #expect(result == "$message")
    }

    @Test func workflowKeepsTeamScopeAndRawTaskDetails() async throws {
        let api = client { request in
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
            #expect(query.contains(URLQueryItem(name: "team", value: "team/a")))
            #expect(query.contains(URLQueryItem(name: "includeTasks", value: "true")))
            return (200, #"{"project_id":"same-id","title":"项目","status":"active","team_id":"team/a","nodes":null,"tasks_detail":null}"#)
        }
        let project = try JSONDecoder().decode(Project.self, from: Data(#"{"project_id":"same-id","title":"项目","status":"active","team_id":"team/a"}"#.utf8))
        let result = try await api.workflow(session(), project: project)
        #expect(result.boardTasks.isEmpty)
    }

    @Test func syncEncodesCursorAndDoesNotLeakBearerInURL() async throws {
        let api = client { request in
            #expect(!request.url!.absoluteString.contains("test-token"))
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
            #expect(query.contains(URLQueryItem(name: "since", value: "cursor+&/")))
            #expect(query.contains(URLQueryItem(name: "timeout", value: "30000")))
            return (200, #"{"next_batch":"next"}"#)
        }
        #expect(try await api.sync(session(), since: "cursor+&/").next_batch == "next")
    }

    @Test func serverFailureIsNotReportedAsEmptyTasks() async throws {
        let api = client { _ in (503, #"{"error":"storage unavailable"}"#) }
        do { _ = try await api.projects(session()); Issue.record("Expected error") }
        catch let error as APIError {
            if case .http(let status, let detail) = error { #expect(status == 503); #expect(detail == "storage unavailable") }
            else { Issue.record("Wrong error") }
        }
    }

    @Test func approvalCommandsTargetEachRuntimeAndRejectInvalidIDs() throws {
        for runtime in ["openclaw", "qwenpaw", "hermes", "deepseek-harness", "openhuman", "codex"] {
            let event = try JSONDecoder().decode(MatrixEvent.self, from: Data("""
            {"event_id":"$approval","sender":"@bot:s","type":"m.room.message","content":{"body":"approve?","org.agentteams.approval":{"version":1,"runtime":"\(runtime)","status":"pending","request_id":"req-1","context_id":"ctx-1","tool_name":"shell"}}}
            """.utf8))
            var room = ChatRoom(id: "!r:s"); room.ingest([event])
            let approval = try #require(Approval(message: room.messages[0], roomID: room.id))
            #expect(approval.command(approve: true).contains("req-1"))
            #expect(approval.command(approve: false).contains("req-1"))
            #expect(approval.answerContent(approve: true, actor: "@alice:s")["m.relates_to"]?.object?["m.in_reply_to"]?.object?["event_id"]?.string == "$approval")
        }
        let event = try JSONDecoder().decode(MatrixEvent.self, from: Data(#"{"event_id":"$bad","type":"m.room.message","content":{"org.agentteams.approval":{"version":1,"runtime":"qwenpaw","status":"pending","request_id":"one\n/approve two","tool_name":"shell"}}}"#.utf8))
        var room = ChatRoom(id: "!r:s"); room.ingest([event])
        #expect(Approval(message: room.messages[0], roomID: room.id) == nil)
    }
}

private final class StubProtocol: URLProtocol, @unchecked Sendable {
    static var handler: ((URLRequest) throws -> (Int, String))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, body) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

private extension URLRequest {
    var bodyData: Data {
        if let httpBody { return httpBody }
        guard let stream = httpBodyStream else { return Data() }
        stream.open(); defer { stream.close() }
        var data = Data(); var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(contentsOf: buffer.prefix(count))
        }
        return data
    }
}
