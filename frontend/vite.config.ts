import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// During local `vite dev`, proxy /api to the backend on :8080.
// In production the app is served by nginx which proxies /api itself.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
