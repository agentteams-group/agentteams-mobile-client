# 移动端接入约定

移动端只消费已有 AT 服务。iOS 和 Android 分别实现 UI、系统凭据存储和生命周期管理，后续鸿蒙也遵守同一约定；本文件记录共用的身份与接口约定。

## 身份与服务地址

用户配置设备可访问的 Matrix 与 Controller 根地址，可以带反向代理路径前缀。地址不可包含用户名、密码、查询串或 fragment。不能把 Docker 容器内部主机名直接当作真机地址。

1. `POST /_matrix/client/v3/login`，使用 `m.login.password`、`m.id.user`。
2. 取得用户 access token 后，使用 `Authorization: Bearer <user-token>` 请求 Controller `GET /api/v1/me`。
3. Controller 确认该身份对应有效 Human 后才保存会话；验证失败时注销新建的 Matrix 会话。
4. 其余 Controller 请求沿用该用户 token，服务端逐次判断权限。客户端不携带共享管理员 token，不替用户提升权限。
5. 密码不保存；iOS token 保存在系统钥匙串，Android 使用 Keystore 的不可导出 AES-GCM 密钥加密会话并存入不参与备份的私有目录。HTTP 客户端拒绝重定向，防止向新地址转发凭据。

401 表示登录失效，用户需要重新登录；403 显示权限不足。网络或服务端错误不可伪装为空列表。

## 现有接口

| 能力 | 接口 |
| --- | --- |
| 当前 Human | `GET /api/v1/me` |
| Manager / Worker 列表 | `GET /api/v1/managers`、`GET /api/v1/workers` |
| 项目与任务 | `GET /api/v1/projects`、`GET /api/v1/projects/{id}/workflow?includeTasks=true&team=...` |
| 待处理事项 | `GET /api/v1/projects/{id}/attention?team=...` |
| 会话同步 | `GET /_matrix/client/v3/sync?since=...&timeout=30000` |
| 房间状态 / 历史 | `GET /_matrix/client/v3/rooms/{roomId}/state`、`.../messages?dir=b&from=...` |
| 发送文字 | `PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{transactionId}` |
| 创建私聊 | `POST /_matrix/client/v3/createRoom`，随后维护 `m.direct` account data |
| 邀请处理 | `POST /_matrix/client/v3/join/{roomId}`、`POST /_matrix/client/v3/rooms/{roomId}/leave` |
| 已读 / 注销 | `POST /_matrix/client/v3/rooms/{roomId}/read_markers`、`POST /_matrix/client/v3/logout` |

Controller 接口按 AgentTeams 主线 `bc2c0649` 的字段契约实现。接入已有部署前需要核对接口可用性；本仓库不包含服务端改动。

## 任务与房间

任务主键使用 `(team_id, project_id, task_id)`，不能只凭 `task_id` 合并任务。查询 workflow 和 attention 时携带项目团队作用域。任务状态优先取 `tasks_detail.status`，保留 `submitted → 待验收`；不能把 workflow 的归一化 `completed` 当作已验收证据。

房间优先采用任务的 `room_id`、`source_room_id`，再使用项目来源房间。没有房间时显示明确提示，不猜测房间、不自动加入其他房间。

助手对话优先复用 `m.direct` 关联的已加入房间。资源的 `roomID` 可能是控制房间，只有当前用户已经加入时才复用；否则创建独立私聊。加入房间需要用户明确操作。消息通过正文 `@name` 与 `m.mentions.user_ids` 指定接收助手。

## 同步与审批

`/sync` 使用 opaque `next_batch` 游标，增量合并房间状态。收到 `limited` 时间线时重置当前时间线并保留新的 `prev_batch`，避免把缺失消息静默拼接。历史查询使用 `messages.end` 继续向前分页。消息编辑验证发送者与原消息一致，撤回移除对应内容。

审批仅接受已知助手发出的 `org.agentteams.approval` v1，按 runtime、request_id、context_id 组装现有协议命令。用户确认后提交到原房间，附带 `m.in_reply_to` 与 `com.agentteams.answer`。同一发送重试复用 transactionId；服务端接收成功后才显示“已提交”，不声称工具已执行。多请求并存时不能发送不带标识的通用批准命令。

## 真机验收

以下项目需要连接实际部署手动验收；契约测试不替代这些结果。

- [ ] 从 Xcode 编译 iPhone 模拟器 App，打开登录页。
- [ ] 构建 Android Debug APK，安装到设备或模拟器，打开登录页。
- [ ] 使用一个有效 Human 登录，再用无权限或错误密码账号验证错误提示。
- [ ] 退出并重启 App：正确恢复会话；退出登录后不再恢复旧账号。
- [ ] 打开 Manager，发送消息并看到真实回复；打开 Worker 私聊并验证 @提及。
- [ ] 收到协作邀请，明确接受后进入房间；拒绝另一邀请。
- [ ] 在两个客户端中验证消息编辑、撤回、未读、历史分页和前后台恢复。
- [ ] 看板展示真实任务，`submitted` 停留在待验收列；从任务进入正确团队的房间。
- [ ] 工作台显示任务待办、工具审批和 @提醒；审批同时存在多个请求时只处理选中的一个。
- [ ] 断网后保留输入；恢复网络重试同一条消息不会重复发送。
- [ ] 配置自己的 Team，在真机签名构建、连接局域网或 HTTPS AT 服务完成以上流程。
- [ ] Android 在前后台切换和旋转屏幕后正确恢复会话，退出登录后不再恢复旧账号。
