// 开发和预览均在同源下转发 API，请求 Cookie 由浏览器处理。
import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), 'RBAC_')
  const objectTarget = process.env.RBAC_OO_API_TARGET || env.RBAC_OO_API_TARGET || 'http://127.0.0.1:18182'
  const functionTarget = process.env.RBAC_FUNC_API_TARGET || env.RBAC_FUNC_API_TARGET || 'http://127.0.0.1:18181'
  const proxy = {
    '/oo/api': { target: objectTarget, rewrite: (path: string) => path.replace(/^\/oo\//, '/') },
    '/func/api': { target: functionTarget, rewrite: (path: string) => path.replace(/^\/func\//, '/') },
  }
  return { plugins: [vue()], server: { proxy }, preview: { proxy } }
})
