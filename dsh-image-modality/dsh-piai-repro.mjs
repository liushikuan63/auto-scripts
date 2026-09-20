/** 最小复现：直接调用 pi-ai 的 openai-completions streamSimple，看错误来自哪一层。 */
import { createServer } from 'node:http';
import { join } from 'node:path';

const DSH_HOME = process.env.DSH_HOME ?? join(process.env.USERPROFILE ?? '', '.dsh');
const streamSimple = (await import(new URL(`file:///${join(DSH_HOME, 'profiles/node_modules/@earendil-works/pi-ai/dist/api/openai-completions.js').replace(/\\/g, '/')}`).href)).streamSimple;
const { createModels, createProvider } = await import(new URL(`file:///${join(DSH_HOME, 'profiles/node_modules/@earendil-works/pi-ai/dist/models.js').replace(/\\/g, '/')}`).href);

const captured = [];
const server = createServer((request, response) => {
  const chunks = [];
  request.on('data', (p) => chunks.push(p));
  request.on('end', () => {
    captured.push(Buffer.concat(chunks).toString('utf8'));
    response.writeHead(200, { 'content-type': 'text/event-stream' });
    const base = { id: 'x', object: 'chat.completion.chunk', created: 1, model: 'probe' };
    for (const frame of [
      { ...base, choices: [{ index: 0, delta: { role: 'assistant' }, finish_reason: null }] },
      { ...base, choices: [{ index: 0, delta: { content: 'ok' }, finish_reason: null }] },
      { ...base, choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] },
    ]) response.write(`data: ${JSON.stringify(frame)}\n\n`);
    response.write('data: [DONE]\n\n');
    response.end();
  });
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const baseUrl = `http://127.0.0.1:${server.address().port}/v1`;

const model = {
  id: 'probe-model', name: 'probe-model', api: 'openai-completions', provider: 'probe', baseUrl,
  input: ['text', 'image'], reasoning: false, contextWindow: 100000, maxTokens: 1000,
  cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
};
const context = { messages: [{ role: 'user', content: [{ type: 'text', text: 'hi' }, { type: 'image', data: 'aGk=', mimeType: 'image/png' }] }] };

for (const [label, run] of [
  ['直接调用 streamSimple', () => streamSimple(model, context, { apiKey: 'probe-key' })],
  ['经 ModelsImpl', () => {
    const models = createModels({});
    models.setProvider(createProvider({
      id: 'probe', name: 'probe', models: [],
      api: { 'openai-completions': { streamSimple } },
      auth: { apiKey: { env: ['PROBE_API_KEY'] } },
    }));
    return models.streamSimple(model, context, { apiKey: 'probe-key' });
  }],
]) {
  try {
    const result = await run().result();
    console.log(`${label}: stopReason=${result?.stopReason} error=${result?.errorMessage ?? '-'} captured=${captured.length} bodyHasImage=${(captured.at(-1) ?? '').includes('image_url')}`);
  } catch (error) {
    console.log(`${label}: 抛出 ${error?.message}`);
  }
}
server.close();
