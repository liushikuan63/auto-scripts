/**
 * 端到端验证 DSH 图片门禁：走真实线格式化链路（pi-ai 的 openai-completions api），
 * 打到本地假网关，检查实际发出的请求体里有没有图片。
 *
 * 判据（正反都要能失败）：
 *   正向：模型声明 input=[text, image] ⇒ 请求体出现 "image_url" 且携带 data:image/png;base64
 *   反向：模型声明 input=[text]        ⇒ 请求体不含 image_url，图片被替换为占位文本
 *
 * 用法：node dsh-image-e2e-probe.mjs
 */
import { createServer } from 'node:http';
import { deflateSync } from 'node:zlib';
import { join } from 'node:path';

const DSH_HOME = process.env.DSH_HOME ?? join(process.env.USERPROFILE ?? '', '.dsh');
const load = (relative) => import(new URL(`file:///${join(DSH_HOME, 'profiles/node_modules/@earendil-works/pi-ai/dist', relative).replace(/\\/g, '/')}`).href);
const { createModels, createProvider } = await load('models.js');
const { envApiKeyAuth } = await load('auth/helpers.js');
const { streamSimple } = await load('api/openai-completions.js');

function twoTonePng() {
  const w = 64, h = 64;
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    let o = y * (w * 3 + 1);
    raw[o++] = 0;
    for (let x = 0; x < w; x++) {
      const left = x < w / 2;
      raw[o++] = left ? 220 : 20; raw[o++] = 20; raw[o++] = left ? 20 : 220;
    }
  }
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; table[n] = c; }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    let crc = -1;
    for (const b of body) crc = table[(crc ^ b) & 0xff] ^ (crc >>> 8);
    const crcBuf = Buffer.alloc(4); crcBuf.writeInt32BE(~crc);
    return Buffer.concat([len, body, crcBuf]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

const captured = [];
const server = createServer((request, response) => {
  const chunks = [];
  request.on('data', (part) => chunks.push(part));
  request.on('end', () => {
    captured.push(Buffer.concat(chunks).toString('utf8'));
    response.writeHead(200, { 'content-type': 'text/event-stream' });
    const base = { id: 'chatcmpl-probe', object: 'chat.completion.chunk', created: 1, model: 'probe' };
    const frames = [
      { ...base, choices: [{ index: 0, delta: { role: 'assistant' }, finish_reason: null }] },
      { ...base, choices: [{ index: 0, delta: { content: 'ok' }, finish_reason: null }] },
      { ...base, choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } },
    ];
    for (const frame of frames) response.write(`data: ${JSON.stringify(frame)}\n\n`);
    response.write('data: [DONE]\n\n');
    response.end();
  });
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const baseURL = `http://127.0.0.1:${server.address().port}/v1`;

const models = createModels({});
models.setProvider(createProvider({
  id: 'probe',
  name: 'probe',
  api: { 'openai-completions': { streamSimple } },
  models: [],
  // 与 pi-ai 内置 provider 同形的鉴权描述（deepseek.js 用 envApiKeyAuth）。
  auth: { apiKey: envApiKeyAuth('probe key', ['PROBE_API_KEY']) },
}));
const png = twoTonePng();
async function run(declared) {
  const model = {
    id: 'probe-model', name: 'probe-model', api: 'openai-completions', provider: 'probe', baseUrl: baseURL,
    input: declared, reasoning: false, contextWindow: 100000, maxTokens: 1000,
    cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
  };
  const context = {
    messages: [{
      role: 'user',
      content: [
        { type: 'text', text: '这张图左半边和右半边分别是什么颜色？' },
        { type: 'image', data: png.toString('base64'), mimeType: 'image/png' },
      ],
    }],
  };
  const result = await models.streamSimple(model, context, { apiKey: 'probe-key' }).result();
  if (captured.length === 0) {
    console.log('（无请求到达本地网关）stream 结果：', JSON.stringify({ stopReason: result?.stopReason, errorMessage: result?.errorMessage }).slice(0, 500));
    console.log('stack:', String(result?.error?.stack ?? result?.errorStack ?? result?.errorMessage).split('\n').slice(0, 8).join('\n'));
  }
  const body = captured.at(-1);
  return {
    hasImageUrl: body.includes('image_url'),
    hasBase64: body.includes('data:image/png;base64'),
    placeholder: /image omitted|does not support images/.test(body),
    bytes: body.length,
  };
}

const declaredImage = await run(['text', 'image']);
const declaredText = await run(['text']);
server.close();

console.log('声明 input=[text, image] 时：', declaredImage);
console.log('声明 input=[text]        时：', declaredText);
const pass = declaredImage.hasImageUrl && declaredImage.hasBase64 && !declaredText.hasImageUrl && declaredText.placeholder;
console.log(`\n结论：${pass ? '通过' : '未通过'} —— 声明可收图时图片真实上线（image_url + base64）；声明纯文本时被替换为占位文本。`);
process.exitCode = pass ? 0 : 1;
