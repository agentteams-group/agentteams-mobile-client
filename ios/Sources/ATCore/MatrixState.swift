import Foundation

public struct MatrixEvent: Codable, Sendable {
    public let event_id: String?
    public let sender: String?
    public let type: String
    public let origin_server_ts: Double?
    public let state_key: String?
    public let redacts: String?
    public let content: [String: JSONValue]
    public var timestamp: Double { origin_server_ts ?? 0 }
    public var replacementID: String? {
        let relation = content["m.relates_to"]?.object
        return relation?["rel_type"]?.string == "m.replace" ? relation?["event_id"]?.string : nil
    }
}

public struct SyncResponse: Decodable, Sendable {
    public let next_batch: String
    public let rooms: Rooms?
    public let account_data: EventBatch?
    public struct Rooms: Decodable, Sendable {
        public let join: [String: JoinedRoom]?
        public let invite: [String: Invite]?
        public let leave: [String: JSONValue]?
    }
    public struct Invite: Decodable, Sendable { public let invite_state: EventBatch? }
    public struct JoinedRoom: Decodable, Sendable {
        public let state: EventBatch?
        public let timeline: Timeline?
        public let unread_notifications: Unread?
    }
    public struct Timeline: Decodable, Sendable {
        public let events: [MatrixEvent]?
        public let prev_batch: String?
        public let limited: Bool?
    }
    public struct Unread: Decodable, Sendable {
        public let notification_count: Int?
        public let highlight_count: Int?
    }
}

public struct EventBatch: Decodable, Sendable { public let events: [MatrixEvent]? }
public struct MessagePage: Decodable, Sendable {
    public let chunk: [MatrixEvent]?
    public let end: String?
    public let state: [MatrixEvent]?
}

public struct ChatMessage: Identifiable, Sendable {
    public let id: String
    public let sender: String
    public let timestamp: Double
    public let body: String
    public let content: [String: JSONValue]
}

public struct ChatRoom: Identifiable, Sendable {
    public let id: String
    public var name: String
    public var members: [String: String] = [:]
    public var encrypted = false
    public var unread = 0
    public var highlights = 0
    public var previousBatch: String?
    public var events: [String: MatrixEvent] = [:]

    public init(id: String, name: String? = nil) { self.id = id; self.name = name ?? id }

    public mutating func ingest(_ incoming: [MatrixEvent]) {
        for event in incoming {
            if event.type == "m.room.name", let value = event.content["name"]?.string { name = value }
            if event.type == "m.room.encryption" { encrypted = true }
            if event.type == "m.room.member", let userID = event.state_key {
                if event.content["membership"]?.string == "join" {
                    members[userID] = event.content["displayname"]?.string ?? userID
                } else { members.removeValue(forKey: userID) }
            }
            if event.type == "m.room.encrypted" { encrypted = true }
            if let key = event.event_id { events[key] = event }
        }
    }

    public var messages: [ChatMessage] {
        let redacted = Set(events.values.filter { $0.type == "m.room.redaction" }
            .compactMap { $0.redacts ?? $0.content["redacts"]?.string })
        let edits = events.values.filter { $0.replacementID != nil && !redacted.contains($0.event_id ?? "") }
        return events.values.filter {
            $0.type == "m.room.message" && $0.replacementID == nil && !redacted.contains($0.event_id ?? "")
        }.map { event in
            let edit = edits.filter { $0.replacementID == event.event_id && $0.sender == event.sender }
                .max { $0.timestamp < $1.timestamp }
            let content = edit?.content["m.new_content"]?.object ?? event.content
            return ChatMessage(id: event.event_id!, sender: event.sender ?? "", timestamp: event.timestamp,
                               body: content["body"]?.string ?? "", content: content)
        }.sorted { $0.timestamp == $1.timestamp ? $0.id < $1.id : $0.timestamp < $1.timestamp }
    }

    public var lastActivity: Double { messages.last?.timestamp ?? 0 }
}

public struct MatrixState: Sendable {
    public var rooms: [String: ChatRoom] = [:]
    public var invites: [String: String] = [:]
    public var directRooms: [String: [String]] = [:]
    public var nextBatch: String?
    public init() {}

    public mutating func apply(_ response: SyncResponse) {
        for event in response.account_data?.events ?? [] where event.type == "m.direct" {
            directRooms = event.content.mapValues(\.strings)
        }
        for (id, invite) in response.rooms?.invite ?? [:] {
            invites[id] = invite.invite_state?.events?.first { $0.type == "m.room.name" }?.content["name"]?.string ?? id
        }
        for (id, joined) in response.rooms?.join ?? [:] {
            var room = rooms[id] ?? ChatRoom(id: id)
            if joined.timeline?.limited == true { room.events = [:]; room.previousBatch = joined.timeline?.prev_batch }
            else if room.previousBatch == nil { room.previousBatch = joined.timeline?.prev_batch }
            room.ingest(joined.state?.events ?? [])
            room.ingest(joined.timeline?.events ?? [])
            if let count = joined.unread_notifications?.notification_count { room.unread = count }
            if let count = joined.unread_notifications?.highlight_count { room.highlights = count }
            rooms[id] = room; invites.removeValue(forKey: id)
        }
        for id in response.rooms?.leave?.keys ?? Dictionary<String, JSONValue>().keys {
            rooms.removeValue(forKey: id); invites.removeValue(forKey: id)
        }
        nextBatch = response.next_batch
    }
}
