# DSH 图片（多模态）识别链路诊断

> 结论摘要：**DSH 里"模型不识别图片"不是模型能力问题，而是配置声明问题。**
> 判定图片能否送进请求的唯一开关是模型目录里的输入模态声明；未声明的模型按纯文本处理，
> 图片在**发出请求之前**就被替换成占位文本，模型根本没机会看到图。

## 一、根因（三段链路，任一环没声明就看不到图）

1. **配置层**：`settings.yaml` 的 `llm-pi-ai.providers.<route>.models[].input`。
   生效值解析顺序（`@deepseek-ai/dsh-llm-pi-ai/lib/index.js` 的 `resolveEntry`）：
   条目 `input` → pi-ai 内置目录 `input` → 路由 `defaultInput` → 缺省 `["text"]`。
   路由不在 pi-ai 内置目录里（如 `commandcode`）且条目没写 `input` ⇒ **只能纯文本**。
2. **运行时层**：`@deepseek-ai/dsh-llm/lib/index.js:2251` ——
   模型 `inputModalities` 不含 `image` 且消息里有图片时，走 `projectImagesForTextModel`，
   把图片换成 `[image omitted because this model accepts text only; attachment sha256:…]`。
3. **协议层（兜底）**：pi-ai `dist/api/transform-messages.js` 的 `downgradeUnsupportedImages`，
   `model.input` 不含 `image` 时把用户/工具结果图片换成 `(image omitted: model does not support images)`。

`read_image` 工具同样按该声明把关（`@deepseek-ai/dsh-tool-fs/lib/index.js:972`），
未声明时报 `cannot read "…" as an image: model "…" does not declare image input`。

## 二、本机实测证据（2026-09-21）

| 判据 | 命令 | 结果 |
|---|---|---|
| 线上配置解析 | `node dsh-validate-settings.mjs` | 129 个已配置模型中补声明前只有 11 个可收图；补后 40 个 |
| 网关真收图 | `node dsh-vision-battery.mjs <modelId...>` | 带图问"左红右蓝"，能正确判读才算通过 |
| 门禁端到端 | `node dsh-image-e2e-probe.mjs` | 声明 `[text,image]` ⇒ 请求体出现 `image_url`+base64；声明 `[text]` ⇒ 换成占位文本 |
| 生产链路正例 | `dsh --profile headless "用 read_image 读取 two-tone-red-blue.png …"` | 模型答"左半边红色，右半边蓝色"（真看图） |
| 生产链路负例 | 隔离 `DSH_HOME` 把该模型改回 `input: [text]` 后同上 | `read_image` 直接报错，模型只能自己解 PNG 字节 |

### commandcode 网关逐模型实测（带图请求）

- **确认收图**：`deepseek/deepseek-v4.1-flash`、`deepseek/deepseek-v4-flash`、`deepseek/deepseek-v4-flash-vision-exp`、
  `moonshotai/Kimi-K3`、`moonshotai/Kimi-K2.7-Code(-Highspeed)`、`moonshotai/Kimi-K2.6`、`moonshotai/Kimi-K2.5`、
  `z-ai/glm-5.3-flash`、`z-ai/glm-5.3-flashx`、`gpt-5.6-sol`、`google/gemini-3.8-flash`、
  `xai/grok-4.6`、`xai/grok-4.5`、`Qwen/Qwen3.8-{Omni-Flash,Max,Max-0902,Flash,27B}`、`Qwen/Qwen3.7-{Plus,Flash}`、
  `Qwen/Qwen3.6-Plus`、`MiniMaxAI/MiniMax-M3`、`xiaomi/mimo-v2.5`、`stepfun/Step-3.7-Flash`、
  `nvidia/nemotron-3-ultra-550b-a55b`、`thinkingmachines/inkling(-small)`、`meta/muse-spark-1.2`
- **确认拒图（保持纯文本）**：`deepseek/deepseek-v4-pro`、`deepseek/deepseek-v4-flash-fast`、
  `zai-org/GLM-5.3/5.2/5.2-Fast/5.1/5`、`gpt-5.6-luna`（不稳定，判读不可靠）、`Qwen/Qwen3.6-Max-Preview`、
  `MiniMaxAI/MiniMax-M2.5`、`meituan/LongCat-2.0`、`tencent/hy4-preview`、`meta/muse-spark-1.2/1.3-contributor`
- **网关直接拒绝**：`MiniMaxAI/MiniMax-M2.7`、`xiaomi/mimo-v2.5-pro`、`stepfun/Step-3.5-Flash`、
  `poolside/laguna-s-2.1-free`、`inclusionai/ling-3.0-flash-sante:free`（404/400 "no endpoints that support image input"）
- **需走 Anthropic 协议、本路由调不通**：`claude-*`（网关要求 `/provider/v1/messages`）
- **套餐不含（403）**：`gpt-5.6-terra`、`gpt-5.5`、`gpt-5.4`、`gpt-5.3-codex`、`gpt-5.4-mini`、
  `google/gemini-3.5/3.6/3.1-flash*`、`sakana/fugu-ultra`、`meta/muse-spark-1.1`

其他路由：`zai-coding-cn` 的内置目录已把 `glm-4.6v`/`glm-5.3-flash`/`glm-5v-turbo` 标为可收图（自动继承）；
`openrouter` 19 个条目中 6 个已被目录标为可收图（脚本里写成显式 `input` 便于审阅）。
`agentrouter`/`sensenova`/`shitapi`/`maas-api`/`geeknow` 不在 pi-ai 目录中且本机无对应 API Key，**未实测**。

## 三、改法与回滚

```powershell
# 预演（只打印将改的行）
node dsh-apply-input-modalities.mjs
# 落盘（自动生成 settings.yaml.bak-<stamp> 备份）
node dsh-apply-input-modalities.mjs --write
# 校验：schema + 声明存在性 + 负向对照未被误声明
node dsh-validate-settings.mjs
```

回滚：把 `%USERPROFILE%\.dsh\settings.yaml.bak-<stamp>` 复制回 `settings.yaml` 即可
（脚本只做"在 `name:` 后插入一行 `input: [text, image]`"，无其他改动）。

## 四、脚本清单

| 脚本 | 用途 |
|---|---|
| `dsh-apply-input-modalities.mjs` | 按实测白名单给配置补 `input: [text, image]`（默认预演） |
| `dsh-validate-settings.mjs` | 用 DSH 自带 schemastery `Config` 校验配置 + 断言声明/未过度声明 |
| `dsh-image-modality-check.mjs` | 解析所有已配置模型的生效模态（三级解析），可选 `--write-png` 产出测试图 |
| `dsh-vision-battery.mjs` | 逐模型直连网关发"左红右蓝"图，判定真收图/拒图 |
| `dsh-image-e2e-probe.mjs` | 本地假网关 + 真实 pi-ai 线格式，验证声明开关决定请求体里有没有图 |
| `dsh-negative-control-setup.mjs` | 生成隔离 `DSH_HOME`（把指定模型改成纯文本），做负向对照用 |

## 五、尚未验证 / 边界

- **GUI 附件路径未做视觉验证**：诊断进程无法操作用户浏览器；已用 `read_image` 工具链 + 网关取证替代，
  首次在 GUI 里贴图时请确认能正常识别。
- `dsh-llm-deepseek`（`deepseek-official` 路由）不在本机配置内，本次未涉及；该适配器有独立的
  `inputModalities` 目录与 Files API 上传路径。
- pi-ai 内置目录里标 `[text,image]` 的模型（如 `zai-coding-cn`/`openrouter` 若干）只按**目录证据**采信，
  这些路由本机无凭据，未做真实请求复验。
