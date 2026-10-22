# 2026-10 依赖升级「假绿」复盘

本篇记录一次真实事故：本地构建连续报「BUILD SUCCESSFUL」，同一批提交推到 GitHub 后连续四次 CI 全红。
根因不是代码逻辑，而是**增量构建与 clean 构建的差别**。

## 现象

| 时间 | 提交 | CI 结果 | 耗时 |
| --- | --- | --- | --- |
| 10-16 19:45 | `build(deps)` A 档升级（ML Kit + AndroidX 引用级） | failure | 1m55s |
| 10-21 17:28 | `docs(code)` 251 个成员补注释 | failure | 36s |
| 10-21 17:29 | `docs(engine)` 清理 3 处失效分段标记 | failure | 43s |
| 10-21 17:30 | `docs(wiki)` 同步架构图 | failure | 42s |
| 10-21 17:36 | `fix(deps)` 回退 AndroidX 版本 | success | 2m35s |
| 10-21 17:46 | `ci` 固化代码健康审查脚本 | success | 2m40s |
| 10-21 23:19 | `ci` 审查门禁扩到八条规则 | success | 2m35s |

四次失败都是同一个任务失败，且**都在 40 秒上下快速失败**，而成功构建需要 2 分半 —— 这本身就是个信号：
它没走到编译阶段，而是在依赖校验阶段就断了。

## 根因

失败任务为 `:app:checkDebugAarMetadata`：

```
Dependency 'androidx.core:core:1.19.1' requires libraries and applications that
depend on it to compile against version 37 or later of the Android APIs.
:app is currently compiled against android-36.
Also, the maximum recommended compile SDK version for Android Gradle plugin 8.9.1 is 36.
```

这里是**两道叠加的硬约束**：

1. **compileSdk 上限**：`androidx.core` 1.19.x 要求 `compileSdk ≥ 37`，而本项目是 36，
   且 AGP 8.9.1 官方推荐的 compileSdk 上限同样是 36。
2. **Kotlin 元数据版本**：`androidx.core` 1.18.0 与 `androidx.work` 2.11.0+ 的 class
   由 Kotlin 2.x 构建，元数据版本 2.1.0，而本项目 Kotlin 是 1.9.22
   （编译期报 `binary version of its metadata is 2.1.0, expected version is 1.9.0`）。
   也就是说 2025 年之后的 AndroidX 产物普遍需要 Kotlin 2.x 工具链。

## 为什么本地没发现

三个叠加原因，每一个单独都足以造成误判：

1. **本地验证用的是增量构建**。`compileDebugKotlin` / `testDebugUnitTest` / `lintDebug` /
   `assembleDebug` 都没有先 `clean`，Gradle 复用了上一次的产物与任务状态，
   `checkDebugAarMetadata` 根本没有被重新执行。
2. **assembleDebug 被判定 UP-TO-DATE**，APK 没有重新打包，时间戳停在旧值。
   当时我据此发现「产物新于源码」这条判据不成立，但当时把原因归到了「最新改动是单测文件」，
   没有追到依赖变更这一层。
3. **用任务探测依赖兼容性时又拿到一次假绿**：第一次跑 `:app:checkDebugAarMetadata` 返回 0，
   原因是该任务同样被判定 UP-TO-DATE；改用 `--rerun-tasks` 强制重跑后，才暴露出
   1.19.0 其实也要求 compileSdk 37（只有 1.18.0 及更早可用）。

## 处置

- 回退 AndroidX：`androidx.core:core-ktx` 回到 `1.12.0`、`androidx.work:work-runtime-ktx` 回到 `2.9.0`
  （当前工具链能用的最高版本），只保留 `ML Kit 16.0.0 → 16.0.1` —— ML Kit 是 Java 产物，
  不受 Kotlin 元数据约束。
- 提交 `299ed6c` 记录了这次回退与成因；此后 CI 恢复为绿。

## 留下的规则

1. **任何依赖改动后，验证必须以 clean 构建为准**：

   ```powershell
   .\gradlew.bat clean
   .\gradlew.bat compileDebugKotlin testDebugUnitTest lintDebug assembleDebug
   ```

   判据里出现「本轮构建退出码」时，默认指 clean 之后的这一串。
2. **用某个 Gradle 任务探测依赖兼容性时必须加 `--rerun-tasks`**，否则拿到的是 UP-TO-DATE 的旧结论。
3. **判「产物新于源码」要看 APK 时间戳**；被判定 UP-TO-DATE 的 assembleDebug 不会更新它，
   时间戳没动就是没重新打包。
4. **AndroidX 的引用级升级不能单独做**，必须与工具链升级（Kotlin 2.x + AGP 9 + compileSdk 37）
   同批，否则 clean 构建必红。当前项目要动这一档，见 [构建与测试](Build-and-Test.md) 的工具链章节。
5. **CI 是最后一道网**：本篇四次红全部由 CI 抓出，本地一次都没抓到。

## 相关

- 提交：`fedfeb6`（引入问题的 A 档升级）、`299ed6c`（回退与说明）
- CI 运行：`38957253703`、`39644151743`、`39644274280`、`39644379558`（红）→ `39645354510` 起（绿）
- 门禁脚本：`scripts/audit-code-health.ps1`、`scripts/audit-naming.ps1`，
  同样遵循「clean 优先」的判据
