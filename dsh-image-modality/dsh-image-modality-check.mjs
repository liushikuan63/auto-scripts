/**
 * DSH 图片识别能力核查脚本（只读诊断，不改配置）
 *
 * 目的：证明「模型不识别图片」是不是配置问题——即 DSH 的模型目录没有为对应
 * 模型声明 image 输入模态，导致图片在发出请求前就被替换成占位文本。
 *
 * 判据（每条都能失败）：
 *   1. 模型目录解析：列出 settings.yaml 里配置的每个模型，其生效输入模态按
 *      pi-ai 内置目录 + 配置覆盖解析（与 dsh-llm-pi-ai 的 resolveEntry 同规则）。
 *   2. 真实请求 A（多模态）：向网关发一张必然可判读的图（左红右蓝两半），
 *      模型回答能区分左右 => 网关与该模型确实支持图片输入。
 *   3. 真实请求 B（纯文本声明）：同一模型、同一张图，但声明 input=[text]
 *      => pi-ai 的 downgradeUnsupportedImages 把图换成占位文本，回答无法判读。
 *      对照 A/B 的差异即「配置声明」造成的差别。
 *
 * 用法：node dsh-image-modality-check.mjs [--only <route>]
 * 凭据：从 DSH_HOME/.credentials.yaml 的 refs 读取，只用于本脚本内联请求，不打印。
 */
import { readFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { createRequire } from 'node:module';
import { join } from 'node:path';

const DSH_HOME = process.env.DSH_HOME ?? join(process.env.USERPROFILE ?? '', '.dsh');
const require = createRequire(join(DSH_HOME, 'profiles', 'noop.cjs'));
const YAML = require('yaml');

const args = process.argv.slice(2);
const onlyIndex = args.indexOf('--only');
const only = onlyIndex >= 0 ? args[onlyIndex + 1] : undefined;
const modelIndex = args.indexOf('--model');
const onlyModel = modelIndex >= 0 ? args[modelIndex + 1] : undefined;

// ---------------------------------------------------------------- 输入
const settings = YAML.parse(readFileSync(join(DSH_HOME, 'settings.yaml'), 'utf8'));
const credentials = YAML.parse(readFileSync(join(DSH_HOME, '.credentials.yaml'), 'utf8'));
const refs = credentials.refs ?? {};
const providers = settings['llm-pi-ai']?.providers ?? {};

// ---------------------------------------------------- pi-ai 内置目录
const catalogProviders = new Set([
  'amazon-bedrock', 'ant-ling', 'anthropic', 'azure-openai-responses', 'baseten', 'cerebras',
  'cloudflare-ai-gateway', 'cloudflare-workers-ai', 'deepseek', 'fireworks', 'github-copilot',
  'google', 'google-vertex', 'groq', 'huggingface', 'kimi-coding', 'minimax', 'minimax-cn',
  'mistral', 'moonshotai', 'moonshotai-cn', 'nvidia', 'openai', 'openai-codex', 'opencode',
  'opencode-go', 'openrouter', 'qwen-token-plan', 'qwen-token-plan-cn',
  'qwen-token-plan-individual', 'together', 'vercel-ai-gateway', 'xai', 'xiaomi',
  'xiaomi-token-plan-ams', 'xiaomi-token-plan-cn', 'xiaomi-token-plan-sgp', 'zai', 'zai-coding-cn',
]);

function catalogEntry(route, id) {
  const dir = join(DSH_HOME, 'profiles/node_modules/@earendil-works/pi-ai/dist/providers/data');
  if (!catalogProviders.has(route)) return undefined;
  try {
    const raw = JSON.parse(readFileSync(join(dir, `${route}.json`), 'utf8'));
    for (const api of Object.values(raw)) if (api[id]) return api[id];
  } catch { /* 目录缺失按未收录处理 */ }
  return undefined;
}

/** 与 dsh-llm-pi-ai resolveEntry 同规则：条目 input > 内置目录 input > 路由 defaultInput > ['text']。 */
function effectiveInput(route, profile, entry) {
  const entryInput = Array.isArray(entry.input) && entry.input.length > 0 ? entry.input : undefined;
  const base = catalogEntry(route, entry.id);
  const routeDefault = Array.isArray(profile.defaultInput) && profile.defaultInput.length > 0
    ? profile.defaultInput : ['text'];
  const source = entryInput ?? base?.input ?? routeDefault;
  return { input: source, source: entryInput ? 'entry.input' : base?.input ? 'pi-ai 内置目录' : '默认(text)' };
}

console.log('== 1. 模型目录解析（决定图片是否被送进请求）==');
const rows = [];
for (const [route, profile] of Object.entries(providers)) {
  for (const entry of profile.models ?? []) {
    const { input, source } = effectiveInput(route, profile, entry);
    rows.push({ route, id: entry.id, input: input.join('+'), source, image: input.includes('image') });
  }
}
for (const row of rows.filter((r) => !only || r.route === only)) {
  console.log(`${row.image ? '可收图' : '纯文本'}  ${row.route} :: ${row.id}  [${row.input}]  来源=${row.source}`);
}
console.log(`\n合计 ${rows.length} 个已配置模型；其中声明可收图 ${rows.filter((r) => r.image).length} 个。`);

console.log('\n== 2/3. 真实请求：带图 vs 纯文本对照（同一模型、同一网关）==');
console.log('判据：带图回答能判读「左红右蓝」= 该模型确实收图；纯文本对照应表现为看不到图或无法判断。');

/** 生成 64x64、左半红右半蓝的 PNG（纯 node，无外部依赖）。 */
function twoTonePng() {
  const w = 64, h = 64;
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    let o = y * (w * 3 + 1);
    raw[o++] = 0;
    for (let x = 0; x < w; x++) {
      const left = x < w / 2;
      raw[o++] = left ? 220 : 20;
      raw[o++] = 20;
      raw[o++] = left ? 20 : 220;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crcTable = chunk.table ??= (() => {
      const t = new Int32Array(256);
      for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c; }
      return t;
    })();
    let crc = -1;
    for (const b of body) crc = crcTable[(crc ^ b) & 0xff] ^ (crc >>> 8);
    const crcBuf = Buffer.alloc(4); crcBuf.writeInt32BE(~crc);
    return Buffer.concat([len, body, crcBuf]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}
const png = twoTonePng();
if (args.includes('--write-png')) {
  const { writeFileSync } = await import('node:fs');
  const pngIndex = args.indexOf('--write-png');
  const target = args[pngIndex + 1] && !args[pngIndex + 1].startsWith('--')
    ? args[pngIndex + 1]
    : join(process.cwd(), 'two-tone-red-blue.png');
  writeFileSync(target, png);
  console.log(`已写出测试图 ${target}（左半红、右半蓝，64x64）`);
}
const prompt = '用一句话回答：这张图左半边和右半边分别是什么颜色？只答颜色。';

/** 直连网关（OpenAI Chat Completions 线格式），withImage 控制是否附带图片内容块。 */
async function probe(profile, entry, withImage, maxTokens = 200) {
  const apiKey = refs[profile.apiKeyEnv];
  if (!apiKey) return { error: `凭据 ${profile.apiKeyEnv} 未配置` };
  const content = withImage
    ? [{ type: 'text', text: prompt }, { type: 'image_url', image_url: { url: `data:image/png;base64,${png.toString('base64')}` } }]
    : prompt;
  let response;
  try {
    response = await fetch(`${profile.baseURL.replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
      body: JSON.stringify({ model: entry.id, max_tokens: maxTokens, messages: [{ role: 'user', content }] }),
    });
  } catch (error) {
    return { error: `网络失败: ${error?.message ?? String(error)}` };
  }
  const text = await response.text();
  if (!response.ok) return { http: response.status, error: text.slice(0, 400) };
  try {
    const body = JSON.parse(text);
    const raw = body.choices?.[0]?.message?.content;
    const answer = (typeof raw === 'string' ? raw : JSON.stringify(raw ?? '')).trim();
    const usage = body.usage ? `usage(in=${body.usage.prompt_tokens},out=${body.usage.completion_tokens})` : 'usage=?';
    return { http: response.status, text: answer, usage };
  } catch {
    return { http: response.status, error: `非 JSON 响应: ${text.slice(0, 200)}` };
  }
}

const targets = [];
if (only) {
  const profile = providers[only];
  for (const entry of profile?.models ?? []) {
    if (onlyModel === undefined || entry.id === onlyModel) targets.push([only, profile, entry]);
  }
} else {
  for (const [route, profile] of Object.entries(providers)) {
    for (const entry of profile.models ?? []) {
      if (!refs[profile.apiKeyEnv]) continue; // 只对已有凭据的路由做真实请求
      const { image } = effectiveInput(route, profile, entry);
      const picked = targets.filter(([r]) => r === route).length;
      if (picked < 2 && (image || picked === 0)) targets.push([route, profile, entry]);
    }
  }
}

console.log(`\n将做真实请求的路由样本：${targets.map(([r, , e]) => `${r}::${e.id}`).join(', ') || '（无可用凭据，仅完成目录解析）'}`);
for (const [route, profile, entry] of targets) {
  for (const withImage of [true, false]) {
    const label = withImage ? '带图请求' : '纯文本对照';
    const result = await probe(profile, entry, withImage, withImage ? 1500 : 200);
    const tail = result.error
      ? `HTTP ${result.http ?? '-'} 错误=${result.error.replace(/\s+/g, ' ')}`
      : `HTTP ${result.http} ${result.usage ?? ''} 回答="${result.text?.slice(0, 160)}"`;
    console.log(`\n[${label}] ${route} :: ${entry.id}\n  ${tail}`);
  }
}
