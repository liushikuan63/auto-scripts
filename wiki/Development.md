# 开发与发布

## 变更原则

- 修复、功能、测试、文档和仓库治理分开提交。
- 一个提交只解决一个可描述、可验证的问题。
- 不提交 APK、构建目录、设备日志、截图、IDE 状态和 `local.properties`。
- 不提交账号、Cookie、令牌、密钥或其他凭据。
- OCR 文字坐标必须明确是左上角还是中心；`OcrHelper.TextBlock.x/y` 是中心。
- 后台组件不能隐式启动用户已经停止或暂停的引擎。

## 提交格式

建议使用 Conventional Commits：

```text
fix(scheduler): attach action to watering alarm intents
feat(farming): add level-aware crop selection
test(policy): cover exact double-event countdown
docs(wiki): document runtime architecture
chore(repo): publish reproducible Gradle wrapper
```

提交正文应说明问题、根因、行为变化和验证命令。不要把多个版本号或互不相关的修复压在一个提交中。

## 验证矩阵

| 变更类型 | 最低验证 |
| --- | --- |
| 纯策略/解析 | 对应 JVM 单元测试 |
| Android 生命周期/服务 | 单元测试、Lint、Debug 编译 |
| OCR 关键词/偏移 | 上述门禁加至少一种目标分辨率设备验证 |
| 调度/开机/通知 | 运行、暂停、停止三种状态的触发测试 |
| Wiki/README | 链接检查和 GitHub 渲染检查 |

## 版本发布

1. 更新 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`。
2. 更新 README 与 Wiki 中的当前版本和行为说明。
3. 执行 `testDebugUnitTest lintDebug assembleDebug`。
4. 在设备上验证录屏授权、无障碍连接、手动暂停、后台恢复和一次完整农场循环。
5. 使用独立发布提交和带说明的 Git 标签。

Release APK 需要维护者自己的签名配置。签名文件、密码和 keystore 属性不得进入仓库。
