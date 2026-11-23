import { handleRequest } from './core.js';
import { D1Store } from './d1-store.js';
import adminHtml from './admin.html';

const ADMIN_HTML_HEADERS = {
  'Content-Type': 'text/html; charset=utf-8',
  'Cache-Control': 'no-cache',
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if ((url.pathname === '/admin' || url.pathname === '/admin/') && request.method === 'GET') {
      return new Response(adminHtml, { headers: ADMIN_HTML_HEADERS });
    }
    if (url.pathname === '/' && request.method === 'GET') {
      return Response.redirect(new URL('/admin', url).toString(), 302);
    }
    if (!env.DB) {
      return new Response(JSON.stringify({ error: 'db_not_configured', message: 'D1 绑定未配置' }), {
        status: 500,
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
      });
    }
    return handleRequest(request, new D1Store(env.DB), env);
  },
};