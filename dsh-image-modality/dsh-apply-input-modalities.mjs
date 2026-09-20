/**
 * 为 settings.yaml 的模型条目补 input: [text, image] 声明（图片识别门禁的唯一开关）。
 *
 * 依据：本机实测（dsh-vision-battery.mjs）——带图请求能正确判读「左红右蓝」的模型，
 * 才写入声明；未实测或实测拒图的模型保持纯文本，避免"过度声明"导致请求中途被网关拒绝。
 *
 * 用法：node dsh-apply-input-modalities.mjs          # 预演（只打印将改动的行，不写文件）
 *       node dsh-apply-input-modalities.mjs --write  # 实际写入（先自动备份）
 */
import { copyFileSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const DSH_HOME = process.env.DSH_HOME ?? join(process.env.USERPROFILE ?? '', '.dsh');
const file = join(DSH_HOME, 'settings.yaml');

/** commandcode：逐模型实测通过（带图可判读左右颜色）。 */
const COMMANDCODE_OK = [
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

/** openrouter：内置 pi-ai 目录已声明可收图，这里显式写出以便审阅（不改变运行时行为）。 */
const OPENROUTER_OK = [
  'dots-studio/dots-3-note-preview:free',
  'google/gemma-4-26b-a4b-it:free',
  'google/gemma-4-31b-it:free',
  'minimax/minimax-m3:free',
  'nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free',
  'openrouter/free',
];

const write = process.argv.includes('--write');
let text = readFileSync(file, 'utf8');
const lines = text.split('\n');

/** 定位 `    <route>:` 到下一个同级键之间的行号区间。 */
function routeRange(route) {
  const start = lines.findIndex((line) => line === `    ${route}:`);
  if (start < 0) throw new Error(`settings.yaml 里找不到路由 ${route}`);
  let end = lines.length;
  for (let i = start + 1; i < lines.length; i++) {
    if (/^ {4}\S/.test(lines[i])) { end = i; break; }
  }
  return [start, end];
}

const plan = [];
const out = [...lines];
const insertions = [];
for (const [route, ids] of [['commandcode', COMMANDCODE_OK], ['openrouter', OPENROUTER_OK]]) {
  const [start, end] = routeRange(route);
  for (const id of ids) {
    const found = [];
    for (let i = start; i < end; i++) if (lines[i] === `        - id: ${id}`) found.push(i);
    if (found.length !== 1) throw new Error(`${route}: 期望唯一匹配 "${id}"，实际 ${found.length} 个`);
    const idLine = found[0];
    const nameLine = idLine + 1;
    if (!/^ {10}name: /.test(lines[nameLine])) throw new Error(`${route}: "${id}" 的下一行不是 name 字段：${lines[nameLine]}`);
    if (/^ {10}input:/.test(lines[nameLine + 1])) { plan.push(`skip  ${route} :: ${id}（已声明）`); continue; }
    insertions.push({ index: nameLine + 1, line: '          input: [text, image]', route, id });
    plan.push(`add   ${route} :: ${id}  → ${idLine + 2} 行`);
  }
}
for (const { index, line } of insertions.sort((a, b) => b.index - a.index)) out.splice(index, 0, line);

const patched = out.join('\n');
console.log(plan.join('\n'));
console.log(`\n共新增 ${insertions.length} 行声明；文件 ${lines.length} 行 → ${out.length} 行。`);

if (!write) {
  console.log('\n（预演模式，未写入。加 --write 实际落盘）');
} else {
  const stamp = new Date().toISOString().replace(/[-:T]/g, '').slice(0, 15);
  copyFileSync(file, `${file}.bak-${stamp}`);
  writeFileSync(file, patched);
  console.log(`\n已写入 ${file}；备份 ${file}.bak-${stamp}`);
}
