import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { handleRequest } from './src/core.js';
import { MemoryStore } from './src/memory-store.js';

const port = Number(process.env.PORT ?? 8787);
const store = new MemoryStore();
const adminKey = process.env.ADMIN_KEY || 'dev-admin';
const env = {
  ADMIN_KEY: adminKey,
  ADMIN_SESSION_HOURS: process.env.ADMIN_SESSION_HOURS,
};
const adminHtmlUrl = new URL('./src/admin.html', import.meta.url);

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? `127.0.0.1:${port}`}`);
  if (url.pathname === '/') {
    res.writeHead(302, { Location: '/admin' });
    res.end();
    return;
  }
  if (url.pathname === '/admin' || url.pathname === '/admin/') {
    // 每次请求读取，改完页面刷新即可生效，不需要重启本地服务。
    const adminHtml = await readFile(adminHtmlUrl, 'utf8');
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-cache' });
    res.end(adminHtml);
    return;
  }

  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const request = new Request(`http://${req.headers.host ?? `127.0.0.1:${port}`}${req.url}`, {
    method: req.method,
    headers: req.headers,
    body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
  });
  try {
    const response = await handleRequest(request, store, env);
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch (error) {
    res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'internal_error', message: String(error?.message ?? error) }));
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`SleepDown class sync dev server: http://127.0.0.1:${port}`);
  console.log(`管理台: http://127.0.0.1:${port}/admin`);
  if (process.env.ADMIN_KEY) console.log('管理员密码来自环境变量 ADMIN_KEY。');
  else console.log(`管理员密码（开发默认值）: ${adminKey}`);
  console.log('数据保存在内存中，进程退出后清空。');
});