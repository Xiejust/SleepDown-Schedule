import { handleRequest } from '../lib/core.js';
import { D1Store } from '../lib/d1-store.js';

/** Cloudflare Pages Function 入口，复用 Worker 的 API 核心与 D1 Store。 */
export async function onRequest(context) {
  return handleRequest(context.request, new D1Store(context.env.DB));
}