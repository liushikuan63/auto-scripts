# 构建与测试

## 环境要求

- JDK 17
- Android SDK Platform 36
- Android SDK Build Tools
- Windows、macOS 或 Linux；示例命令以 PowerShell 为主

仓库不提交 `local.properties` 或 `org.gradle.java.home`，因为它们包含开发机路径。请在本机配置 `JAVA_HOME`，并创建 `local.properties`：

```properties
sdk.dir=D:\\Android\\Sdk
```

## Gradle Wrapper

仓库提交以下可复现构建入口：

- `gradlew` / `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `settings.gradle.kts`

Wrapper 通过腾讯云 Gradle 分发镜像下载 Gradle 8.11.1，以兼容当前 Windows 开发环境的证书链限制。首次执行还需要访问项目配置的 Maven 仓库。

## 本地验证

```powershell
# 单元测试
.\gradlew.bat testDebugUnitTest

# Android 静态检查
.\gradlew.bat lintDebug

# Debug APK
.\gradlew.bat assembleDebug

# 连续执行主要门禁
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

报告与产物：

- 测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`
- Lint 报告：`app/build/reports/lint-results-debug.html`
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`

## 测试范围

当前 JVM 测试覆盖：

- 游戏场景文字识别规则。
- OCR 倒计时解析。
- 作物数据库、推荐策略和卡双倍时长匹配。
- 周末双倍、保护时段、土地与赛季规则。
- 配置默认值。
- 坐标手势、随机延迟和 APK 模板读取。

Robolectric 模板测试首次运行需要下载对应 Android 运行时依赖；仓库不再永久跳过这些测试。

MediaProjection、无障碍手势和真实游戏页面需要设备测试。涉及坐标、OCR 关键词或前后台生命周期的改动，必须补充真机或模拟器验证记录。

## CI

GitHub Actions 使用 JDK 17 和 Gradle 缓存，依次执行单元测试、Lint 与 Debug 编译。任何任务失败都会阻断验证工作流，不使用 Lint baseline 隐藏新增错误。
