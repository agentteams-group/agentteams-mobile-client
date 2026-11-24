import SwiftUI
import ATCore

struct ChatView: View {
    var store: AppStore
    let destination: RoomDestination
    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""
    @State private var target = ""
    @State private var transactionID = UUID().uuidString
    @State private var ready = false
    @State private var roomError: String?
    @State private var loadingHistory = false

    private var room: ChatRoom? { store.matrix.rooms[destination.id] }
    private var assistants: [Assistant] {
        store.allAssistants.filter { assistant in
            guard let userID = assistant.matrixUserID else { return false }
            return room?.members[userID] != nil || destination.target == userID
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let error = roomError {
                    ErrorBanner(text: error)
                    HStack {
                        Button("重试") { Task { await prepare() } }
                        if !ready { Button("加入此房间") { Task { await store.join(destination.id); await prepare() } } }
                    }.padding(8)
                }
                if room?.encrypted == true {
                    ContentUnavailableView("这个房间已启用端到端加密", systemImage: "lock.bubble",
                                           description: Text("请在支持加密的 Matrix 客户端中继续对话。"))
                } else {
                    timeline
                    if !ready && roomError == nil { ProgressView("正在连接房间…").padding() }
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if room?.encrypted != true { composer }
            }
            .navigationTitle(destination.name)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() } } }
            .task { await prepare() }
            .onChange(of: room?.messages.last?.id) { _, _ in Task { await store.markRead(destination.id) } }
            .onChange(of: room?.id) { _, id in
                if id == nil { ready = false; roomError = "你已离开这个房间。" }
            }
            .alert("操作未完成", isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) {
                Button("知道了", role: .cancel) { store.error = nil }
            } message: { Text(store.error ?? "") }
        }
    }

    private var timeline: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 18) {
                    if room?.previousBatch != nil {
                        Button(loadingHistory ? "加载中…" : "加载更早消息") {
                            loadingHistory = true
                            Task {
                                do { try await store.olderMessages(destination.id) }
                                catch { roomError = error.localizedDescription }
                                loadingHistory = false
                            }
                        }.frame(maxWidth: .infinity).disabled(loadingHistory)
                    }
                    ForEach(room?.messages ?? []) { message in
                        MessageBubble(store: store, message: message, roomID: destination.id).id(message.id)
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }.padding(16)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: room?.messages.last?.id) { _, _ in proxy.scrollTo("bottom", anchor: .bottom) }
        }
    }

    private var composer: some View {
        VStack(spacing: 10) {
            if !assistants.isEmpty {
                Picker("发送给", selection: $target) {
                    Text("发送到房间").tag("")
                    ForEach(assistants) { assistant in Text("@\(assistant.name)").tag(assistant.matrixUserID ?? "") }
                }.pickerStyle(.menu).frame(maxWidth: .infinity, alignment: .leading)
                    .onChange(of: target) { _, _ in transactionID = UUID().uuidString }
            }
            HStack(alignment: .bottom, spacing: 12) {
                TextField("输入消息…", text: $draft, axis: .vertical)
                    .lineLimit(1...6).textFieldStyle(.plain).padding(12)
                    .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 18))
                    .onChange(of: draft) { _, _ in transactionID = UUID().uuidString }
                Button {
                    let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    let sentDraft = draft
                    Task {
                        if await store.send(room: destination.id, text: text, target: target.isEmpty ? nil : target, transactionID: transactionID) {
                            if draft == sentDraft { draft = "" }
                            transactionID = UUID().uuidString
                        }
                    }
                } label: {
                    if store.sending { ProgressView().frame(width: 42, height: 42) }
                    else { Image(systemName: "arrow.up.circle.fill").font(.system(size: 38)).frame(width: 42, height: 42) }
                }
                .disabled(!ready || store.sending || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityLabel("发送消息")
            }
        }.padding(12).background(.bar)
    }

    private func prepare() async {
        ready = false; roomError = nil
        do {
            try await store.loadRoom(destination.id)
            guard !Task.isCancelled else { return }
            ready = true
            target = destination.target ?? (assistants.count == 1 ? assistants[0].matrixUserID ?? "" : "")
            await store.markRead(destination.id)
        } catch {
            if !Task.isCancelled { roomError = error.localizedDescription }
        }
    }
}

private struct MessageBubble: View {
    var store: AppStore
    let message: ChatMessage
    let roomID: String
    private var mine: Bool { message.sender == store.session?.userID }
    var body: some View {
        HStack(alignment: .top) {
            if mine { Spacer(minLength: 28) }
            VStack(alignment: .leading, spacing: 7) {
                HStack {
                    Text(store.matrix.rooms[roomID]?.members[message.sender] ?? message.sender).lineLimit(1)
                    Spacer(minLength: 8)
                    Text(Date(timeIntervalSince1970: message.timestamp / 1000), style: .time)
                }.font(.caption).foregroundStyle(.secondary)
                Text(.init(message.body)).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(13)
                    .background(mine ? Color.green.opacity(0.12) : Color.secondary.opacity(0.07), in: RoundedRectangle(cornerRadius: 16))
                if let approval = store.approvals.first(where: { $0.eventID == message.id && $0.roomID == roomID }) {
                    ApprovalActions(store: store, approval: approval)
                }
                ForEach(store.questions.filter { $0.eventID == message.id && $0.roomID == roomID }) { question in
                    QuestionActions(store: store, request: question)
                }
            }
            if !mine { Spacer(minLength: 28) }
        }
    }
}

private struct QuestionActions: View {
    var store: AppStore
    let request: QuestionRequest
    @State private var selected: [String: Set<String>] = [:]
    @State private var custom: [String: String] = [:]
    @State private var transaction = UUID().uuidString
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("等待人工 · 需要你的回答", systemImage: "questionmark.bubble")
            ForEach(request.questions) { question in
                Text(question.text).font(.subheadline)
                ForEach(question.options) { option in
                    Button {
                        var choices = selected[question.id] ?? []
                        if choices.contains(option.id) { choices.remove(option.id) }
                        else if question.multiSelect { choices.insert(option.id) }
                        else { choices = [option.id] }
                        selected[question.id] = choices
                    } label: {
                        Label(option.label, systemImage: (selected[question.id] ?? []).contains(option.id) ? "checkmark.circle.fill" : "circle")
                    }
                }
                TextField("补充回答", text: Binding(get: { custom[question.id] ?? "" }, set: { custom[question.id] = $0 }))
                    .textFieldStyle(.roundedBorder)
            }
            if store.submittedApprovals.contains(request.id) {
                Text("已提交，等待运行时确认").font(.caption)
            } else {
                Button("提交回答") { Task { _ = await store.answer(request, selected: selected, custom: custom, transactionID: transaction) } }
                    .disabled(store.sending || request.response(selected: selected, custom: custom, actor: store.session?.userID ?? "") == nil)
            }
        }
        .padding(12).background(Color.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
        .disabled(store.submittedApprovals.contains(request.id))
    }
}

private struct ApprovalActions: View {
    var store: AppStore
    let approval: Approval
    @State private var choice: Bool?
    @State private var approveTransaction = UUID().uuidString
    @State private var rejectTransaction = UUID().uuidString
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("工具审批 · \(approval.tool)", systemImage: "hand.raised")
            if !approval.parameters.isEmpty { Text(approval.parameters).font(.caption.monospaced()).textSelection(.enabled) }
            HStack {
                Button("批准本次") { choice = true }.buttonStyle(.borderedProminent)
                Button("拒绝", role: .destructive) { choice = false }.buttonStyle(.bordered)
            }.disabled(store.sending)
        }
        .padding(12).frame(maxWidth: .infinity, alignment: .leading)
        .background(.orange.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
        .confirmationDialog(choice == true ? "批准这次工具调用？" : "拒绝这次工具调用？", isPresented: Binding(get: { choice != nil }, set: { if !$0 { choice = nil } }), titleVisibility: .visible) {
            let approve = choice == true
            Button(approve ? "批准本次" : "拒绝本次", role: approve ? nil : .destructive) {
                Task { _ = await store.answer(approval, approve: approve, transactionID: approve ? approveTransaction : rejectTransaction) }
                choice = nil
            }
        } message: { Text("仅处理请求 \(approval.requestID)，结果将发送到当前协作房间。") }
    }
}
