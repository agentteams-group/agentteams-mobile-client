# AgentTeams Mobile · Android

原生 Kotlin / Jetpack Compose 接入端，连接已有 AT，不包含运行内核或服务端。

## 功能

与 iOS 首期范围对齐：Matrix 密码登录、Manager/Worker 对话、任务看板与结果摘要、任务直达协作房间、工作台待办、工具审批、邀请与 @提醒。

会话支持前台增量同步、历史分页、Markdown 基础展示、选择 @助手、编辑与撤回展示、已读回执。同一消息失败重试复用 transactionId；输入在屏幕旋转时保留。

## 构建

- JDK 17，Gradle Wrapper 8.12.1。
- Android SDK Platform 35、Build Tools 35.0.0。
- Android 8.0+（API 26），compileSdk / targetSdk 为 35。
- AGP 8.9.2、Kotlin 2.1.20、Compose BOM 2025.04.01，依赖固定版本。

Android Studio 打开本目录，配置 SDK 和 Gradle JDK 后直接运行 `app`。也可以只装 Android 命令行工具：

```bash
sdkmanager 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
# 设置 ANDROID_HOME，或在未入库的 local.properties 指定 sdk.dir
./gradlew :core:test :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS 的 SDK 默认位置通常为 `~/Library/Android/sdk`，命令行可先设置 `export ANDROID_HOME="$HOME/Library/Android/sdk"`。本机验证使用 JDK 17；可用 `export JAVA_HOME="$(/usr/libexec/java_home -v 17)"` 选择已安装的 JDK。其他系统按自己的 SDK/JDK 位置配置即可。

Windows 对应使用 `gradlew.bat`。Debug APK 自动采用本机调试签名，可直接安装。`./gradlew :app:assembleRelease` 生成未签名的 Release APK；正式签名可在 Android Studio 使用自己的证书完成，不依赖 Play 上架。证书、口令、SDK 本机路径均不提交到仓库。

## 连接 AT

输入手机可访问的 Matrix、Controller 根地址，用现有 Human 账号登录。支持反向代理路径前缀。Controller 必须支持用户 Matrix token 以及项目 workflow / attention 接口，约定见 [接入说明](../docs/integration.md)。

- 真机使用开发电脑的局域网 IP 或 HTTPS 域名，不能使用容器内部域名。
- Android 官方模拟器访问宿主机可用 `10.0.2.2`。
- Debug 允许 HTTP 用于可信局域网测试；Release 只允许 HTTPS。两个构建都保持系统证书校验，不接受自签名证书绕过。
- token 使用 Android Keystore AES-GCM 加密，密文放在 `noBackupFilesDir`；密码不保存到磁盘或实例状态。退出时删除密文和对应密钥。

## 测试

```bash
# JVM 契约测试，不需要设备或真实 AT
./gradlew :core:test

# 编译 App、Lint 和 Compose 设备测试 APK
./gradlew :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest

# 需要已连接的设备或启动的模拟器
./gradlew :app:connectedDebugAndroidTest
```

根目录也可以执行 `bash scripts/check-android.sh`。JVM 测试使用本地 MockWebServer，覆盖身份传递、登录失败注销、任务隔离、待验收状态、消息路径、重定向拒绝、同步取消、消息修订与撤回、邀请、加密房间识别、六种运行时审批命令及多请求隔离。

Compose 设备测试验证登录表单的空值保护和输入传递，不连接真实 AT。设备测试 APK 能编译不代表测试已在设备上执行。真实会话、Keystore、前后台恢复、任务跳转与审批仍需按 [验收清单](../docs/integration.md#真机验收) 联调。

## 首期边界

不包含团队和 Admin 管理界面、第三方插件、SSO、推送、后台常驻、离线消息数据库、E2EE、附件上传下载、语音和话题线程。检测到加密房间会禁用发送；旧版纯文本审批可在原房间手动答复。
