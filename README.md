# FarmBot

FarmBot 是面向 Android 10 及以上设备的王者农场自动化实验项目。应用通过无障碍手势、MediaProjection 截图、ML Kit 中文 OCR 和本地图像识别完成农场导航、种植、浇水、收获、好友访问及定时调度。

> 本项目仅用于个人学习、移动端自动化与图像识别研究。使用自动化工具可能违反目标应用的服务条款，并可能带来账号限制等风险。请在理解风险后自行决定是否使用。

## 当前状态

- 当前版本：`1.4.37`（`versionCode 42`）
- 最低系统：Android 10（API 29）
- 编译目标：Android API 36
- 构建环境：JDK 17、Gradle 8.11.1、Android Gradle Plugin 8.9.1
- 验证任务：JVM 单元测试、Android Lint、Debug APK 编译

## 主要能力

- 识别目标游戏前后台状态，并按配置启动或暂停引擎。
- 通过录屏截图与中文 OCR 定位 SurfaceView 中的文字和操作入口。
- 通过“一键务农”统一执行收获、种植和浇水。
- 根据农场等级、工作日/周末双倍时段和自定义配置选择作物。
- 控制偷菜、祝福、出售、开垦和每日对战流程。
- 使用 AlarmManager 与 WorkManager 组合调度，并阻止引擎停止或暂停时被后台任务隐式启动。
- 提供悬浮控制面板、实时日志、模板采集和 APK 资源导入工具。

## 快速构建

1. 安装 JDK 17 与 Android SDK API 36。
2. 在仓库根目录创建本机专用的 `local.properties`：

   ```properties
   sdk.dir=C:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
   ```

3. 执行验证和构建：

   ```powershell
   .\gradlew.bat testDebugUnitTest
   .\gradlew.bat lintDebug
   .\gradlew.bat assembleDebug
   ```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 首次运行

1. 授予悬浮窗权限。
2. 在系统设置中启用 FarmBot 无障碍服务。
3. 在主界面授权屏幕录制；真实 OCR 点击依赖该通道。
4. 设置目标应用、农场等级与自动化策略。
5. 打开目标游戏并从主界面或悬浮面板启动引擎。

## 文档

- [Wiki 首页](wiki/Home.md)
- [系统架构](wiki/Architecture.md)
- [自动化流程](wiki/Automation-Flow.md)
- [配置参考](wiki/Configuration.md)
- [构建与测试](wiki/Build-and-Test.md)
- [故障排查](wiki/Troubleshooting.md)
- [开发与发布](wiki/Development.md)
- [代码审查记录](wiki/Code-Review-2026-09-05.md)

## 仓库结构

```text
app/src/main/java/com/farmbot/
|-- accessibility/   无障碍手势与前台应用检测
|-- config/          配置模型与持久化
|-- engine/          状态机、动作执行与调度
|-- ocr/             ML Kit 中文 OCR
|-- policy/          作物数据与玩法规则
|-- recognition/     模板采集和匹配
|-- screenshot/      MediaProjection 截图服务
|-- service/         前台服务与悬浮窗服务
|-- ui/              Compose 页面和悬浮界面
|-- vision/          图像识别与 APK 模板导入
`-- work/            WorkManager 兜底任务
```

提交问题前，请附上 Android 版本、设备分辨率、复现步骤和经过脱敏的相关日志。不要上传账号信息、授权凭据或完整设备日志。
