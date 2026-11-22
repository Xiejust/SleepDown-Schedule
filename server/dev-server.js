import http from 'node:http';
import { handleRequest } from './src/core.js';
import { MemoryStore } from './src/memory-store.js';

const port = Number(process.env.PORT ?? 8787);
const store = new MemoryStore();

const server = http.createServer(async (req, res) => {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const request = new Request(`http://${req.headers.host ?? `127.0.0.1:${port}`}${req.url}`, {
    method: req.method,
    headers: req.headers,
    body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
  });
  try {
    const response = await handleRequest(request, store);
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch (error) {
    res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'internal_error', message: String(error?.message ?? error) }));
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`SleepDown class sync dev server: http://127.0.0.1:${port}`);
  console.log('数据保存在内存中，进程退出后清空。');
});