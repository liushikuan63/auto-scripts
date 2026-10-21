# 系统架构

## 分层

```text
Compose UI / 悬浮窗 / 通知动作
                |
            FarmEngine
                |
        FarmStateMachine ------ TaskScheduler
          |         |                |       |
  状态处理器(5)  支撑类(3)      AlarmManager WorkManager
          |         |                |       |
 FarmStateContext  OCR/模板/走位      WateringAlarmReceiver
          |
 FarmRuntime（共享依赖、计数、日志）
          |
   ActionExecutor / 无障碍手势 / 截图
```

### 应用与界面

- `FarmBotApp` 初始化日志、配置、内置模板和策略管理器。
- `MainActivity` 负责权限检查、录屏授权和主要 Compose 界面。
- `SettingsActivity` / `SettingsDialog` 编辑 `FarmConfig`。
- `FloatingWindowService` 管理悬浮控制面板与日志窗。
- `FarmBotForegroundService` 提供常驻通知和暂停、恢复、停止、立即浇水命令。

### 引擎

- `FarmEngine` 是应用级编排入口，维护 `RUNNING`、`PAUSED`、`STOPPED` 状态。
- `FarmStateMachine` 串行执行农场状态，负责状态流转、单状态 3 分钟超时与三级恢复调度。
- 业务状态实现在 `engine/states/` 的 5 个处理器（导航扫描、农场操作、好友拜访、对战升级、异常恢复），
  OCR 定位与点击、走位、一键务农在 `engine/runtime/` 的 3 个支撑类，
  共享依赖、计数与日志集中在 `FarmRuntime`。
- `FarmStateContext` 是处理器与状态机之间的接缝：处理器只依赖该接口，可脱离状态机独立构造与测试。
- `ActionExecutor` 封装模板识别、OCR 与无障碍点击链。
- `TaskScheduler` 设置下一次精确闹钟并管理每日对战检查任务。

### 感知与动作

- `ScreenshotService` 通过 MediaProjection 提供最新截图副本。
- `OcrHelper` 使用 ML Kit 中文识别，`TextBlock.x/y` 表示文字边界框中心。
- `TemplateMatcher` 与 `ImageRecognizer` 负责模板、颜色和成熟作物识别。
- `FarmAccessibilityService` 执行点击、滑动和全局返回等动作。

### 策略与配置

- `FarmConfig` 是不可变配置模型。
- `ConfigManager` 同步维护内存缓存和 SharedPreferences，并串行同步到 DataStore。
- `CropDatabase` 保存作物解锁等级、生长时长和收益数据。
- `GameRules` 保存双倍时段、保护时段、土地解锁与 S45 规则。
- `PolicyManager` 负责可导入/导出的策略配置。

## 关键数据流

### OCR 点击

1. 状态机向 `ScreenshotService` 获取一帧 Bitmap。
2. `OcrHelper` 一次识别全部文本块。
3. 状态机按关键词优先级选择文本块中心。
4. 校准偏移按当前截图相对 `1920x1080` 的比例缩放。
5. `FarmAccessibilityService` 添加小范围随机偏移并派发手势。
6. 后续页面文字或状态负责验证动作是否生效。

### 配置持久化

`ConfigManager.save` 先同步更新缓存与 SharedPreferences，使运行时立即可见；DataStore 写入在单一持久化作用域中串行执行，并通过版本号丢弃过期保存，避免快速连续操作时旧值覆盖新值。

### 后台调度

精确 Alarm 使用显式 `WateringAlarmReceiver` Intent 和固定 action。WorkManager 每 15 分钟提供兜底检查。两条通道最终都调用 `FarmEngine.triggerWateringNow()`，而该入口只接受 `RUNNING` 状态。
