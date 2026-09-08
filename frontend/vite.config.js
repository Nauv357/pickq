import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发阶段：/api 代理到本地后端（http://localhost:8080），避免跨域
// 生产阶段：前端 dist 由后端同源托管，axios baseURL 相对路径 /api 保持不变
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
