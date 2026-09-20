/** 批量探测 commandcode 网关各模型是否真的收图：只带图请求，输出 id/HTTP/回答/usage。 */
import { readFileSync } from 'node:fs';
import { deflateSync } from 'node:zlib';
import { createRequire } from 'node:module';
import { join } from 'node:path';

const DSH_HOME = process.env.DSH_HOME ?? join(process.env.USERPROFILE ?? '', '.dsh');
const require = createRequire(join(DSH_HOME, 'profiles', 'noop.cjs'));
const YAML = require('yaml');
const settings = YAML.parse(readFileSync(join(DSH_HOME, 'settings.yaml'), 'utf8'));
const refs = YAML.parse(readFileSync(join(DSH_HOME, '.credentials.yaml'), 'utf8')).refs ?? {};
const profile = settings['llm-pi-ai'].providers.commandcode;
const apiKey = refs[profile.apiKeyEnv];

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
const png = twoTonePng();
const prompt = '用一句话回答：这张图左半边和右半边分别是什么颜色？只答颜色。';

async function probe(model) {
  let response;
  try {
    response = await fetch(`${profile.baseURL.replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
      body: JSON.stringify({
        model, max_tokens: 400,
        messages: [{
          role: 'user',
          content: [
            { type: 'text', text: prompt },
            { type: 'image_url', image_url: { url: `data:image/png;base64,${png.toString('base64')}` } },
          ],
        }],
      }),
    });
  } catch (error) {
    return { verdict: 'NETWORK', detail: error?.message ?? String(error) };
  }
  const text = await response.text();
  if (!response.ok) {
    let detail = text;
    try { detail = JSON.parse(text).error?.message ?? text; } catch { /* 保留原文 */ }
    return { verdict: `HTTP_${response.status}`, detail: String(detail).replace(/\s+/g, ' ').slice(0, 120) };
  }
  const body = JSON.parse(text);
  const answer = (body.choices?.[0]?.message?.content ?? '').toString().trim().replace(/\s+/g, ' ');
  const usage = `in=${body.usage?.prompt_tokens} out=${body.usage?.completion_tokens}`;
  const sees = /红|red/i.test(answer) && /蓝|blue/i.test(answer);
  return { verdict: sees ? 'IMAGE_OK' : 'NO_IMAGE', detail: `${usage} ${answer.slice(0, 90)}` };
}

const ids = process.argv.slice(2);
for (const id of ids) {
  const result = await probe(id);
  console.log(`${result.verdict}\t${id}\t${result.detail}`);
}
