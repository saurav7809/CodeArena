import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig, loadEnv } from 'vite';

export default defineConfig(({ mode }) => {
  const apiOrigin = (loadEnv(mode, process.cwd(), 'VITE_').VITE_API_URL || '').trim().replace(/\/$/, '');
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
      proxy: { '/api': 'http://127.0.0.1:8080' },
    },
    preview: {
      host: '127.0.0.1',
      proxy: { '/api': 'http://127.0.0.1:8080' },
    },
  };
});
