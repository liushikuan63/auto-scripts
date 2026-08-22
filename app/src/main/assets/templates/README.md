# 预置模板目录

把"开箱即用"的模板图片放到这里，首次启动 App 时会通过 `AssetTemplateInstaller`
自动复制到外部存储（`FarmBot/templates/{buttons,crops,states}/`），用户首次安装即可使用。

## 文件命名约定（决定复制到哪个子目录）

| 前缀 | 目标子目录 | 示例 |
|------|----------|------|
| `btn_` 或 `icon_` | `buttons/` | `btn_water.png`, `btn_harvest.png`, `icon_watering_can.png` |
| `crop_` | `crops/` | `crop_mature.png`, `crop_immature.png` |
| 其它 | `states/` | `area_countdown.png` |

## 推荐预置模板清单（17 个）

参见 `TemplateCollector.CollectStep`：

- `btn_water.png` 浇水按钮
- `btn_harvest.png` 收获按钮
- `btn_steal.png` 偷菜按钮
- `btn_battle.png` 对战开始按钮
- `btn_sell.png` 出售按钮
- `btn_friend_enter.png` 好友入口
- `btn_enter_farm.png` 农场入口
- `btn_confirm.png` 确认按钮
- `btn_close.png` 关闭按钮
- `btn_expand_land.png` 开地按钮
- `btn_upgrade.png` 升级按钮
- `btn_surrender.png` 投降按钮
- `btn_match_confirm.png` 匹配确认
- `crop_mature.png` 成熟作物
- `crop_immature.png` 未成熟作物
- `icon_watering_can.png` 浇水壶图标
- `area_countdown.png` 倒计时区域

## 注意

- 不同分辨率 / 游戏版本下模板可能失配，建议首次运行后通过"采集模板"页重新采集覆盖。
- 文件大小建议 ≤ 50 KB（PNG，含 alpha 通道更佳）。
- 若不放任何文件，App 仍能正常启动；只是首次使用需要用户手动采集模板。
