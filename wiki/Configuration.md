# 配置参考

配置模型位于 `app/src/main/java/com/farmbot/config/FarmConfig.kt`。

## 浇水与对战

| 字段 | 默认值 | 说明 |
| --- | ---: | --- |
| `waterIntervalMin` | `120` | 下一次浇水间隔，单位分钟。 |
| `autoAdjustByPlant` | `true` | 根据植物状态自动调整间隔。 |
| `inheritCountdown` | `false` | 使用 OCR 倒计时更新间隔。 |
| `dailyBattle` | `true` | 是否处理每日对战入口。 |
| `surrenderAfterSec` | `180` | 对战提示使用的投降等待秒数；当前不自动完成对局。 |
| `newMode` | `true` | 使用新赛季模式提示。 |

## 农场策略

| 字段 | 默认值 | 说明 |
| --- | ---: | --- |
| `farmLevel` | `1` | 决定作物、土地和功能解锁；运行时可由 OCR 校正。 |
| `cropStrategy` | `AUTO` | 智能、经验优先或金币优先。 |
| `weekdayCrop` | 空 | 周一至周四自定义作物；空值自动选经验作物。 |
| `weekendCrop` | 空 | 双倍期自定义作物；空值自动选金币作物。 |
| `weekendDoubleAware` | `true` | 启用双倍时段及双倍前卡点选种。 |
| `hoardUntilWeekend` | `false` | 非双倍期暂缓出售的策略提示。 |
| `sellMode` | `NONE` | 不出售、全部出售或仅出售满级作物。 |

自定义作物必须存在于 `CropDatabase` 且当前等级已解锁。等级降低或旧配置无效时，运行时自动回退，不会尝试点击不可用种子。

## 社交与升级

| 字段 | 默认值 | 说明 |
| --- | ---: | --- |
| `enableSteal` | `true` | 启用好友农场拿取。 |
| `enableBless` | `true` | 启用好友祝福。 |
| `prioritySteal` | `false` | 跳过自家农场，优先访问好友。 |
| `respectStealProtection` | `true` | 0:00 至 8:00 不执行偷菜。 |
| `stealBeforeBless` | `true` | 控制先偷后祝或先祝后偷。 |
| `stealDailyLimit` | `120` | 每日真实偷菜计数上限。 |
| `blessDailyLimit` | `10` | 每日真实祝福计数上限。 |
| `upgradePriority` | `LAND_FIRST` | 土地、小摊或均衡优先提示。 |
| `upgradeExpandAfter` | `true` | 升级后开地策略开关。 |
| `expandAlways` | `false` | 每轮主动寻找开垦入口。 |

## 安全与联动

| 字段 | 默认值 | 说明 |
| --- | ---: | --- |
| `operationDelayMinMs` | `800` | 单株模拟操作最小延迟。 |
| `operationDelayMaxMs` | `2500` | 单株模拟操作最大延迟。 |
| `maxDailyOperations` | `500` | 状态机当次运行的业务操作上限。 |
| `dailyOperationLimit` | `500` | 无障碍点击计数兼容上限。 |
| `randomOffsetRange` | `8` | 点击坐标随机偏移像素。 |
| `templateMatchThreshold` | `0.85` | 模板匹配阈值。 |
| `autoStartWhenGameForeground` | `true` | 游戏进入前台且录屏就绪时自动启动。 |
| `pauseWhenGameBackground` | `false` | 游戏离开前台时自动暂停。 |
| `targetPackages` | 王者荣耀包名 | 需要监控的目标应用集合。 |

配置通过 SharedPreferences 提供即时读取，通过 DataStore 提供 Flow 和持久化副本。不要在配置中保存账号、密码、令牌或其他凭据。
