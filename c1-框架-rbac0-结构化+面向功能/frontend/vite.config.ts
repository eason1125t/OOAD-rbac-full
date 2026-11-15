// 开发和预览均在同源下转发 API，请求 Cookie 由浏览器处理。
import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'RBAC_')
  const proxy = { '/api': process.env.RBAC_API_TARGET || env.RBAC_API_TARGET || 'http://127.0.0.1:8080' }
  return { plugins: [vue()], server: { proxy }, preview: { proxy } }
})
