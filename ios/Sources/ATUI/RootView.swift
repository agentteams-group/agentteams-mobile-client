import SwiftUI
import ATCore

private let brand = Color(red: 0.16, green: 0.55, blue: 0.34)

@MainActor
public struct RootView: View {
    @State private var store = AppStore()
    @Environment(\.scenePhase) private var scenePhase
    @State private var accountPresented = false

    public init() {}

    public var body: some View {
        Group {
            if store.session == nil { LoginView(store: store) }
            else {
                TabView(selection: $store.selectedTab) {
                    navigation("工作台") { WorkbenchView(store: store) }
                        .tabItem { Label("工作台", systemImage: "square.grid.2x2") }.tag(0)
                    navigation("我的助手") { AssistantsView(store: store) }
                        .tabItem { Label("我的助手", systemImage: "sparkles") }.tag(1)
                    navigation("任务看板") { TaskBoardView(store: store) }
                        .tabItem { Label("任务", systemImage: "rectangle.split.3x1") }.tag(2)
                    navigation("协作房间") { RoomsView(store: store) }
                        .tabItem { Label("房间", systemImage: "bubble.left.and.bubble.right") }.tag(3)
                }
                .sheet(item: $store.selectedRoom) { destination in
                    ChatView(store: store, destination: destination)
                }
                .sheet(isPresented: $accountPresented) { AccountView(store: store) }
            }
        }
        .tint(brand)
        .task(id: scenePhase == .active ? store.session?.id : nil) {
            if scenePhase == .active { await store.run() }
        }
        .alert("操作未完成", isPresented: Binding(get: { store.error != nil && store.selectedRoom == nil }, set: { if !$0 { store.error = nil } })) {
            Button("知道了", role: .cancel) { store.error = nil }
        } message: { Text(store.error ?? "") }
    }

    private func navigation<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        NavigationStack {
            content()
                .navigationTitle(title)
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button { accountPresented = true } label: { Image(systemName: "person.crop.circle") }
                            .accessibilityLabel("账号与连接")
                    }
                }
                .safeAreaInset(edge: .top, spacing: 0) {
                    if let error = store.syncError { ErrorBanner(text: "会话同步：\(error)") }
                    if !store.resourceErrors.isEmpty {
                        DisclosureGroup("部分数据暂不可用（\(store.resourceErrors.count)）") {
                            ForEach(store.resourceErrors, id: \.self) { Text($0).font(.caption).frame(maxWidth: .infinity, alignment: .leading) }
                            Button("重试") { Task { await store.refresh() } }.disabled(store.loading)
                        }
                        .font(.footnote).padding(12).background(.orange.opacity(0.1))
                    }
                }
        }
    }
}

private struct LoginView: View {
    @Bindable var store: AppStore
    @AppStorage("matrixAddress") private var matrix = ""
    @AppStorage("controllerAddress") private var controller = ""
    @State private var username = ""
    @State private var password = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 14) {
                        Image(systemName: "bubble.left.and.bubble.right.fill").font(.system(size: 36)).foregroundStyle(brand)
                        Text("和你的 Agent 团队\n一起推进工作").font(.largeTitle).fontWeight(.medium)
                        Text("登录 AT，继续对话、跟进任务、参与协作。").foregroundStyle(.secondary)
                    }.padding(.vertical, 20)
                }.listRowBackground(Color.clear)
                Section {
                    addressField("Matrix 地址，例如 https://matrix.example.com", text: $matrix)
                    addressField("Controller 地址，例如 https://at.example.com", text: $controller)
                } header: { Text("连接你的 AT") } footer: {
                    Text("填写 iPhone 能访问的服务地址，沿用现有 AT 账号。手机上的 localhost 指向手机自身。")
                }
                Section("账号") {
                    TextField("用户名或完整 Matrix ID", text: $username).textContentType(.username)
                        .autocorrectionDisabled()
                    SecureField("密码", text: $password).textContentType(.password)
                }
                Section {
                    Button {
                        Task { if await store.login(matrix: matrix, controller: controller, username: username, password: password) { password = "" } }
                    } label: {
                        HStack {
                            Spacer()
                            if store.authenticating { ProgressView() }
                            Text(store.authenticating ? "正在登录…" : "登录")
                            Spacer()
                        }.padding(.vertical, 6)
                    }
                    .disabled(store.authenticating || matrix.isEmpty || controller.isEmpty || username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || password.isEmpty)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("AgentTeams")
        }
    }

    private func addressField(_ placeholder: String, text: Binding<String>) -> some View {
        TextField(placeholder, text: text).autocorrectionDisabled()
        #if os(iOS)
            .keyboardType(.URL).textInputAutocapitalization(.never)
        #endif
    }
}

private struct AccountView: View {
    var store: AppStore
    @Environment(\.dismiss) private var dismiss
    @State private var confirmLogout = false
    var body: some View {
        NavigationStack {
            Form {
                Section("当前账号") {
                    LabeledContent("名称", value: store.profile?.name ?? "同步中")
                    Text(store.session?.userID ?? "").textSelection(.enabled)
                }
                Section("服务地址") {
                    LabeledContent("Matrix") { Text(store.session?.matrix.base.absoluteString ?? "").textSelection(.enabled) }
                    LabeledContent("Controller") { Text(store.session?.controller.base.absoluteString ?? "").textSelection(.enabled) }
                }
                Section {
                    Button("退出登录", role: .destructive) { confirmLogout = true }
                } footer: { Text("登录凭据保存在系统钥匙串，密码不会保存。") }
            }
            .navigationTitle("账号与连接")
            .toolbar { Button("完成") { dismiss() } }
            .confirmationDialog("退出当前账号？", isPresented: $confirmLogout, titleVisibility: .visible) {
                Button("退出登录", role: .destructive) { dismiss(); Task { await store.logout() } }
            }
        }
    }
}

private struct WorkbenchView: View {
    var store: AppStore
    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("你好，\(store.profile?.name ?? "")").font(.title2)
                    Text("需要你参与的工作，都在这里。").foregroundStyle(.secondary)
                }.padding(.vertical, 8)
                if let manager = store.managers.first {
                    Button { Task { await store.openAssistant(manager) } } label: {
                        Label("与 Manager 对话", systemImage: "sparkles")
                    }.disabled(store.openingAssistant)
                }
            }
            Section("待处理事项 · \(store.attention.count)") {
                if store.attention.isEmpty { emptyRow("没有等待你答复的任务") }
                ForEach(store.attention) { work in
                    Button {
                        if let id = work.item.room_id, !id.isEmpty { store.openRoom(id, name: work.item.task_title) }
                        else { store.error = "这个待办尚未关联房间，请与 Manager 对话处理。" }
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(work.item.question).foregroundStyle(.primary)
                            Text("\(work.projectTitle) · \(work.item.task_title)").font(.caption).foregroundStyle(.secondary)
                        }.padding(.vertical, 4)
                    }
                }
            }
            Section("待审批 · \(store.approvals.count)") {
                if store.approvals.isEmpty { emptyRow("没有待处理的工具审批") }
                ForEach(store.approvals) { approval in
                    Button { store.openRoom(approval.roomID) } label: {
                        Label(approval.tool, systemImage: "hand.raised").foregroundStyle(.primary)
                    }
                }
            }
            Section("房间邀请 · \(store.matrix.invites.count)") {
                if store.matrix.invites.isEmpty { emptyRow("没有新的房间邀请") }
                ForEach(store.matrix.invites.keys.sorted(), id: \.self) { id in
                    InviteRow(store: store, id: id)
                }
            }
            Section("@我的提醒") {
                let rooms = store.rooms.filter { $0.highlights > 0 }
                if rooms.isEmpty { emptyRow("没有未读的 @提醒") }
                ForEach(rooms) { room in
                    Button { store.openRoom(room.id) } label: {
                        HStack { Text(room.name).foregroundStyle(.primary); Spacer(); Text("\(room.highlights)").foregroundStyle(brand) }
                    }
                }
            }
        }
        .overlay { if store.loading && store.profile == nil { ProgressView("正在加载工作台…") } }
        .refreshable { await store.refresh() }
    }
}

private struct InviteRow: View {
    var store: AppStore
    let id: String
    @State private var busy = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(store.matrix.invites[id] ?? id)
            HStack {
                Button("加入房间") { perform { await store.join(id) } }.buttonStyle(.borderedProminent)
                Button("拒绝", role: .destructive) { perform { await store.rejectInvite(id) } }.buttonStyle(.bordered)
            }.disabled(busy)
        }.padding(.vertical, 4)
    }
    private func perform(_ action: @escaping @MainActor () async -> Void) {
        busy = true
        Task { await action(); busy = false }
    }
}

private struct AssistantsView: View {
    var store: AppStore
    var body: some View {
        List {
            Section("Manager") { ForEach(store.managers) { row($0, manager: true) } }
            Section("我的助手") { ForEach(store.workers) { row($0, manager: false) } }
        }
        .overlay {
            if store.allAssistants.isEmpty && !store.loading {
                ContentUnavailableView("还没有可用助手", systemImage: "sparkles", description: Text("下拉刷新，或通过 Manager 创建你的助手。"))
            }
        }
        .refreshable { await store.refresh() }
    }
    private func row(_ assistant: Assistant, manager: Bool) -> some View {
        Button { Task { await store.openAssistant(assistant) } } label: {
            HStack(spacing: 14) {
                Image(systemName: manager ? "sparkles" : "person.crop.square").font(.title2).foregroundStyle(brand)
                    .frame(width: 44, height: 44).background(brand.opacity(0.08), in: RoundedRectangle(cornerRadius: 12))
                VStack(alignment: .leading, spacing: 5) {
                    Text(assistant.name).foregroundStyle(.primary)
                    Text([assistant.runtime, assistant.phase].compactMap { $0 }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
            }.padding(.vertical, 5)
        }.disabled(store.openingAssistant)
    }
}

private struct RoomsView: View {
    var store: AppStore
    @State private var search = ""
    var body: some View {
        List {
            ForEach(store.rooms.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }) { room in
                Button { store.openRoom(room.id) } label: {
                    HStack(spacing: 12) {
                        Image(systemName: room.encrypted ? "lock.bubble" : "bubble.left.and.bubble.right")
                            .font(.title2).foregroundStyle(brand).frame(width: 38)
                        VStack(alignment: .leading, spacing: 6) {
                            Text(room.name).foregroundStyle(.primary).lineLimit(1)
                            Text(room.encrypted ? "加密房间" : room.messages.last?.body ?? "还没有消息")
                                .font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        }
                        Spacer()
                        if room.unread > 0 { Text("\(room.unread)").font(.caption).padding(6).background(brand.opacity(0.12), in: Capsule()) }
                    }.padding(.vertical, 6)
                }
            }
        }
        .searchable(text: $search, prompt: "搜索房间")
        .overlay { if store.rooms.isEmpty { ContentUnavailableView("还没有协作房间", systemImage: "bubble.left.and.bubble.right", description: Text("从我的助手开始对话，或在工作台接受房间邀请。")) } }
    }
}

struct ErrorBanner: View {
    let text: String
    var body: some View { Text(text).font(.footnote).frame(maxWidth: .infinity, alignment: .leading).padding(12).background(.orange.opacity(0.1)) }
}

private func emptyRow(_ text: String) -> some View { Text(text).font(.subheadline).foregroundStyle(.secondary) }
