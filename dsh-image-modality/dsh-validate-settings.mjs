/**
 * 用 DSH 自带的 schemastery 校验 settings.yaml 的 llm-pi-ai.providers 段
 * （与实际加载同一份 Config schema），并对补丁后的 input 声明做存在性断言。
 *
 * 用法：node dsh-validate-settings.mjs [settings.yaml 路径]
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { createRequire } from 'node:module';

const DSH_HOME = process.env.DSH_HOME ?? join(process.env.USERPROFILE ?? '', '.dsh');
const require = createRequire(join(DSH_HOME, 'profiles', 'noop.cjs'));
const YAML = require('yaml');
const file = process.argv[2] ?? join(DSH_HOME, 'settings.yaml');

const { Config } = await import(
  new URL(`file:///${join(DSH_HOME, 'profiles/node_modules/@deepseek-ai/dsh-llm-pi-ai/lib/index.js').replace(/\\/g, '/')}`).href
);

const settings = YAML.parse(readFileSync(file, 'utf8'));
const providers = settings['llm-pi-ai']?.providers ?? {};

let resolved;
try {
  resolved = Config({ providers });
  console.log('schema 校验：通过');
} catch (error) {
  console.log('schema 校验：失败');
  console.log(String(error?.message ?? error));
  process.exitCode = 1;
}

const expected = [
  'deepseek/deepseek-v4.1-flash',
  'deepseek/deepseek-v4-flash',
  'deepseek/deepseek-v4-flash-vision-exp',
  'moonshotai/Kimi-K3',
  'moonshotai/Kimi-K2.7-Code',
  'moonshotai/Kimi-K2.7-Code-Highspeed',
  'moonshotai/Kimi-K2.6',
  'moonshotai/Kimi-K2.5',
  'z-ai/glm-5.3-flash',
  'z-ai/glm-5.3-flashx',
  'gpt-5.6-sol',
  'google/gemini-3.8-flash',
  'xai/grok-4.6',
  'xai/grok-4.5',
  'Qwen/Qwen3.8-Omni-Flash',
  'Qwen/Qwen3.8-Max',
  'Qwen/Qwen3.8-Max-0902',
  'Qwen/Qwen3.8-Flash',
  'Qwen/Qwen3.8-27B',
  'Qwen/Qwen3.7-Plus',
  'Qwen/Qwen3.7-Flash',
  'Qwen/Qwen3.6-Plus',
  'MiniMaxAI/MiniMax-M3',
  'xiaomi/mimo-v2.5',
  'stepfun/Step-3.7-Flash',
  'nvidia/nemotron-3-ultra-550b-a55b',
  'thinkingmachines/inkling',
  'thinkingmachines/inkling-small',
  'meta/muse-spark-1.2',
];

const commandcode = resolved.providers.commandcode;
const byId = new Map((commandcode.models ?? []).map((model) => [model.id, model]));
let bad = 0;
for (const id of expected) {
  const model = byId.get(id);
  const input = model?.input ?? [];
  if (!input.includes('image')) { console.log(`缺声明  ${id}  input=${JSON.stringify(input)}`); bad++; }
}
console.log(`commandcode：应声明可收图 ${expected.length} 个，缺 ${bad} 个。`);

const openrouter = resolved.providers.openrouter;
const orImage = (openrouter.models ?? []).filter((model) => (model.input ?? []).includes('image')).map((m) => m.id);
console.log(`openrouter：显式声明可收图 ${orImage.length} 个 → ${orImage.join(', ')}`);
console.log(`未声明（按默认纯文本处理）的 openrouter 模型 ${orImage.length === 0 ? '' : ''}: ${(openrouter.models ?? []).filter((m) => !(m.input ?? []).includes('image')).map((m) => m.id).join(', ')}`);

/** 未实测拒图的模型必须保持纯文本（防止过度声明）。 */
const mustStayText = ['zai-org/GLM-5.3', 'zai-org/GLM-5.2', 'zai-org/GLM-5.1', 'zai-org/GLM-5', 'zai-org/GLM-5.2-Fast',
  'deepseek/deepseek-v4-pro', 'deepseek/deepseek-v4-flash-fast', 'meituan/LongCat-2.0', 'tencent/hy4-preview',
  'Qwen/Qwen3.6-Max-Preview', 'gpt-5.6-luna', 'gpt-5.6-terra', 'MiniMaxAI/MiniMax-M2.5', 'miniMaxAI/MiniMax-M2.7',
  'meta/muse-spark-1.2-contributor', 'meta/muse-spark-1.3-contributor'];
let overClaim = 0;
for (const id of mustStayText) {
  const model = byId.get(id);
  if (model && (model.input ?? []).includes('image')) { console.log(`过度声明  ${id}`); overClaim++; }
}
console.log(`对照组（实测拒图/未证实）被误声明为可收图的：${overClaim} 个。`);
