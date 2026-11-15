# AgentTeams Mobile Client

AgentTeams 的移动接入端。连接已有的 AT 服务，使用现有账号对话、查看任务、参与协作。这里不包含 Agent 运行内核、任务调度器或服务端部署。

首期提供 iOS 和 Android 原生接入端，App 显示名称为 **AgentTeams Mobile**。两个平台分别构建，共用接入约定。鸿蒙尚未实现，后续可在 `harmony/` 中维护 ArkTS 工程。

```text
android/
  app/                        Kotlin / Jetpack Compose App、Keystore
  core/                       HTTP 接入、Matrix 同步、任务模型与测试
  gradlew                     固定版本的 Gradle 构建入口
ios/
  AgentTeamsMobile.xcodeproj/  Xcode App 工程
  App/                        App 入口与系统配置
  Sources/ATCore/              HTTP 接入、Matrix 同步、任务模型
  Sources/ATUI/                SwiftUI 页面、会话状态、钥匙串
  Tests/ATCoreTests/           接口契约与状态测试
docs/                         共用接入说明
scripts/check.sh              本地校验
```

## 首期功能

- 登录：Matrix 用户名、密码；使用同一用户 token 访问 Controller。登录状态由 iOS 系统钥匙串或 Android Keystore 加密保护，密码不落盘。
- 我的助手：查看有权限访问的 Manager 和 Worker，复用已有会话或创建私聊。
- 对话：收发文字、Markdown 基础展示、选择 @助手、增量同步、加载更早消息、消息编辑与撤回、未读回执。
- 任务看板：按状态展示任务，查看任务结果摘要，进入对应协作房间。“已提交”显示为“待验收”。
- 工作台：任务待处理事项、结构化工具审批、房间邀请和 @提醒。待处理事项进入原房间继续答复；审批按钮携带请求标识提交。
- 协作房间：浏览已加入房间、搜索房间、接收与拒绝邀请、发送消息。

团队创建、Admin 管理、第三方插件和鸿蒙客户端不在本期范围。可以继续通过 Manager 对话管理团队和助手。

## Android 本地构建

支持 **Android 8.0+（API 26）**，使用 Kotlin 和 Jetpack Compose。安装 JDK 17、Android SDK 35 和 Build Tools 35.0.0，或直接用 Android Studio 打开 `android/`。

```bash
cd android
./gradlew :core:test :app:assembleDebug :app:lintDebug
# 可直接安装的 Debug APK：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 使用 `gradlew.bat`。Debug APK 自动使用本地调试签名，无需先发布到应用商店。正式签名使用你自己的证书，不要把证书提交到仓库。完整说明见 [Android 构建与验收](android/README.md)。

## iOS 本地构建

需要安装完整 **Xcode 16 或更新版本**及 iOS Simulator SDK。支持 **iOS 17+**；不依赖第三方 Swift 包，也无需部署新的服务端。

1. 打开 `ios/AgentTeamsMobile.xcodeproj`，选择 `AgentTeamsMobile` scheme。
2. 选择 iPhone 模拟器，直接 Run。
3. 在 App 填写 Matrix、Controller 地址，用现有 AT Human 账号登录。地址必须能从设备访问；真机上的 `localhost` 指向手机自身。
4. 真机运行：在 Signing & Capabilities 选择你自己的 Team；必要时修改 Bundle Identifier，然后选择已连接的 iPhone 运行。签名由你自己的 Xcode 账号完成。

```bash
open ios/AgentTeamsMobile.xcodeproj

# 不签名构建模拟器 App
xcodebuild -project ios/AgentTeamsMobile.xcodeproj \
  -scheme AgentTeamsMobile -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath ios/build/DerivedData CODE_SIGNING_ALLOWED=NO build

# 生成未签名的本地归档，不能直接安装到 iPhone
xcodebuild -project ios/AgentTeamsMobile.xcodeproj \
  -scheme AgentTeamsMobile -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath ios/build/AgentTeamsMobile.xcarchive \
  CODE_SIGNING_ALLOWED=NO archive
```

需要可安装的 App 时，在 Xcode 选择自己的 Team、以签名方式运行或归档。无需先上架 App Store。服务端地址、密码、开发者 Team 都没有写死在工程中。

Debug 构建允许 HTTP，方便连接局域网测试部署；HTTP 不加密账号和消息，只用于可信网络。Release 使用系统 ATS，远程接入请配置 HTTPS。使用局域网服务时允许系统的“本地网络”权限。

## 校验

```bash
bash scripts/check.sh
# Android：契约测试、Lint、Debug APK 与设备测试 APK 编译
bash scripts/check-android.sh
```

`check.sh` 检查 iOS 工程 plist、编译共享 SwiftUI 代码、运行 Swift Testing 契约测试；检测到完整 Xcode 和模拟器 SDK 时还会构建 iOS App。只有 Command Line Tools 时会明确跳过 iOS App 构建。`check-android.sh` 要求已安装 Android SDK，不会把缺少 SDK 的构建当作成功。

测试不需要账号或运行中的 AT，不会向真实房间发消息。它覆盖身份传递、登录失败清理 token、房间路径转义、跨团队任务隔离、审批请求关联、同步增量、分页游标、编辑、撤回与离开房间。

两个平台都需要使用真实 AT 账号完成模拟器和真机联调。编译和契约测试不等于真机业务流程已经验收。完整验收清单见 [接入说明](docs/integration.md)。

## 使用边界

- 首期账号入口是 Matrix 密码登录。企业 SSO、账号注册和密码找回尚未接入。
- 房间消息只在前台同步；没有系统推送和后台常驻，也没有离线消息数据库。
- 暂不支持 Matrix E2EE；检测到加密房间后禁用发送，请在支持加密的客户端继续对话。
- 暂不支持文件上传下载、语音输入、富交互卡片和话题线程。文本消息、结果摘要与结构化工具审批是首期交互范围。
- 原生审批按钮识别 `org.agentteams.approval` v1；旧版纯文本审批可以在房间中按提示手动答复。
- 待处理事项通过 Controller attention 接口读取；没有该接口的旧部署会显示接口错误，需要升级服务端。
