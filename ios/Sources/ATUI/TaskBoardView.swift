import SwiftUI
import ATCore

struct TaskBoardView: View {
    var store: AppStore
    @State private var search = ""

    var body: some View {
        GeometryReader { geometry in
            ScrollView(.horizontal) {
                HStack(alignment: .top, spacing: 16) {
                    ForEach(TaskStage.allCases, id: \.self) { stage in
                        let tasks = store.tasks.filter { $0.stage == stage && (search.isEmpty || $0.title.localizedCaseInsensitiveContains(search) || $0.projectTitle.localizedCaseInsensitiveContains(search)) }
                        VStack(alignment: .leading, spacing: 14) {
                            HStack { Circle().fill(stage.color).frame(width: 8, height: 8); Text(stage.rawValue); Spacer(); Text("\(tasks.count)").foregroundStyle(.secondary) }
                            ScrollView {
                                LazyVStack(spacing: 12) {
                                    if tasks.isEmpty { Text("暂无任务").font(.subheadline).foregroundStyle(.secondary).padding(.vertical, 24) }
                                    ForEach(tasks) { task in
                                        NavigationLink(value: task.id) {
                                            VStack(alignment: .leading, spacing: 12) {
                                                Text(task.title).font(.headline).foregroundStyle(.primary).multilineTextAlignment(.leading)
                                                Text(task.projectTitle).font(.caption).foregroundStyle(.secondary)
                                                Label(task.assignee, systemImage: "person.crop.circle").font(.caption).foregroundStyle(.secondary)
                                            }
                                            .frame(maxWidth: .infinity, alignment: .leading).padding(16)
                                            .background(.background, in: RoundedRectangle(cornerRadius: 16))
                                        }.buttonStyle(.plain)
                                    }
                                }
                            }
                        }
                        .padding(14).frame(width: min(320, geometry.size.width - 44))
                        .background(stage.color.opacity(0.06), in: RoundedRectangle(cornerRadius: 20))
                    }
                }.padding(16)
            }
        }
        .searchable(text: $search, prompt: "搜索任务或项目")
        .toolbar { Button { Task { await store.refresh() } } label: { Image(systemName: "arrow.clockwise") }.disabled(store.loading).accessibilityLabel("刷新任务") }
        .navigationDestination(for: String.self) { id in
            if let task = store.tasks.first(where: { $0.id == id }) { TaskDetailView(store: store, task: task) }
            else { ContentUnavailableView("任务已更新", systemImage: "arrow.clockwise", description: Text("返回看板查看最新任务。")) }
        }
    }
}

private struct TaskDetailView: View {
    var store: AppStore
    let task: BoardTask
    var body: some View {
        List {
            Section {
                Text(task.title).font(.title2)
                LabeledContent("状态", value: task.stage.rawValue)
                LabeledContent("项目", value: task.projectTitle)
                LabeledContent("负责人", value: task.assignee)
                LabeledContent("任务编号", value: task.taskID)
            }
            if let summary = task.summary, !summary.isEmpty {
                Section("任务结果") { Text(.init(summary)).textSelection(.enabled) }
            }
            Section {
                if let id = task.roomID {
                    Button { store.openRoom(id, name: task.title) } label: { Label("进入协作房间", systemImage: "bubble.left.and.bubble.right") }
                } else {
                    Text("这个任务尚未关联协作房间，可与 Manager 对话跟进。").foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("任务详情")
    }
}

extension TaskStage {
    var color: Color {
        switch self {
        case .pending: return .gray
        case .active: return .blue
        case .review: return .orange
        case .completed: return .green
        case .blocked: return .red
        case .cancelled, .unknown: return .secondary
        }
    }
}
