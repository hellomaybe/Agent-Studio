import { readFileSync } from 'node:fs';
import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig(({ mode }) => {
  const port = loadEnv(mode, '.', 'STUDIO_').STUDIO_API_PORT ?? '8080';
  if (!/^[1-9]\d{0,4}$/.test(port) || Number(port) > 65535) {
    throw new Error('STUDIO_API_PORT must be a TCP port from 1 to 65535.');
  }
  const proxy = { '/api': { target: `http://127.0.0.1:${port}`, changeOrigin: false } };
  return {
    plugins: [
      vue(),
      {
        name: 'include-upstream-notices',
        generateBundle() {
          for (const name of ['frontend-runtime.txt', 'Vben-MIT.txt']) {
            this.emitFile({
              type: 'asset',
              fileName: 'licenses/' + name,
              source: readFileSync(new URL('../licenses/' + name, import.meta.url)),
            });
          }
        },
      },
    ],
    server: { host: '127.0.0.1', port: 5173, strictPort: true, proxy },
    preview: { host: '127.0.0.1', proxy },
  };
});
