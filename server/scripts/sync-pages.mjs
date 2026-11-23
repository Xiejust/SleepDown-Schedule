/** 把 Worker 的通用核心与管理台页面同步到 Pages，避免两份代码不一致。 */
import { copyFile, mkdir } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const serverRoot = dirname(dirname(fileURLToPath(import.meta.url)));
const sourceDir = join(serverRoot, 'src');
const targetDir = join(serverRoot, 'pages', 'functions', 'lib');
const pagesDist = join(serverRoot, 'pages', 'dist');

await mkdir(targetDir, { recursive: true });
for (const name of ['core.js', 'd1-store.js']) {
  await copyFile(join(sourceDir, name), join(targetDir, name));
}
await mkdir(pagesDist, { recursive: true });
await copyFile(join(sourceDir, 'admin.html'), join(pagesDist, 'admin.html'));
console.log(`synced core.js, d1-store.js -> ${targetDir}`);
console.log(`synced admin.html -> ${pagesDist}`);