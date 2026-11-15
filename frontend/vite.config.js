import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const apiOrigin = (loadEnv(mode, process.cwd(), 'VITE_').VITE_API_URL || '').trim().replace(/\/$/, '');
  const apiPort = Number(process.env.CODEARENA_API_PORT || 8080);
  if (!Number.isInteger(apiPort) || apiPort < 1 || apiPort > 65535) {
    throw new Error('Set CODEARENA_API_PORT to a valid local API port.');
  }
  const localApi = 'http://127.0.0.1:' + apiPort;
  if ((mode === 'deployment' || apiOrigin) && (!apiOrigin.startsWith('https://') || URL.parse(apiOrigin)?.origin !== apiOrigin)) {
    throw new Error('Set VITE_API_URL to the API’s HTTPS origin, without a path.');
  }
  const plugins = [react(), tailwindcss()];
  if (apiOrigin) plugins.push({
    name: 'codearena-content-security-policy',
    apply: 'build',
    transformIndexHtml() {
      return [{
        tag: 'meta',
        attrs: {
          'http-equiv': 'Content-Security-Policy',
          content: [
            "default-src 'self'",
            "connect-src 'self' " + apiOrigin,
            "worker-src 'self' blob:",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data:",
            "object-src 'none'",
            "base-uri 'self'",
            "form-action 'self'",
          ].join('; '),
        },
        injectTo: 'head-prepend',
      }];
    },
  });
  return {
    plugins,
    server: {
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: { '/api': localApi },
    },
    preview: {
      host: '127.0.0.1',
      proxy: { '/api': localApi },
    },
  };
});
