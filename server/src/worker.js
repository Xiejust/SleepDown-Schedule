import { handleRequest } from './core.js';
import { D1Store } from './d1-store.js';

export default {
  async fetch(request, env) {
    if (!env.DB) {
      return new Response(JSON.stringify({ error: 'db_not_configured', message: 'D1 绑定未配置' }), {
        status: 500,
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
      });
    }
    return handleRequest(request, new D1Store(env.DB));
  },
};