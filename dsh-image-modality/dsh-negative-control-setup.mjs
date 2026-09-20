/** 生成隔离的 DSH_HOME（负向对照用）：把某个模型强制改成纯文本声明，不动线上 settings.yaml。 */
import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const src = process.env.DSH_HOME ?? join(process.env.USERPROFILE ?? '', '.dsh');
const out = process.argv[2] ?? join(process.cwd(), '.dsh-negative-control');
const model = process.argv[3] ?? 'deepseek/deepseek-v4.1-flash';

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
for (const name of ['settings.yaml', '.credentials.yaml']) cpSync(join(src, name), join(out, name));
cpSync(join(src, 'AGENTS.md'), join(out, 'AGENTS.md'));

const file = join(out, 'settings.yaml');
const lines = readFileSync(file, 'utf8').split('\n');
const idLine = lines.findIndex((line) => line === `        - id: ${model}`);
if (idLine < 0) throw new Error(`找不到模型 ${model}`);
if (!/^ {10}input: \[text, image\]$/.test(lines[idLine + 2])) throw new Error(`${model} 的第 3 行不是 input 声明：${lines[idLine + 2]}`);
lines[idLine + 2] = '          input: [text]';
writeFileSync(file, lines.join('\n'));
console.log(`已生成隔离 DSH_HOME：${out}（${model} 改为 input: [text]）`);
