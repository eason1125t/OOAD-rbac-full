// 文件职责：为页面提供认证SharedWorker的消息客户端及登录身份变化事件。
// 协作关系：api.ts取得内存Bearer，App.vue响应登录/退出；RPC随机ID不参与用户或令牌版本比较。
import { ApiError } from './http'
import type { AccessLease, AuthChange, AuthCommand, AuthEvent, AuthReply } from './auth-protocol'
import type { Session } from './api'

const editionCode = /^\/(func|oo)(?:\/|$)/.exec(location.pathname)?.[1]
const edition = editionCode ? '/' + editionCode as '/func' | '/oo' : undefined
let port: MessagePort | undefined
let loginId: string | undefined
let unavailable = false
const pending = new Map<string, { resolve: (value: unknown) => void; reject: (error: Error) => void }>()
const listeners = new Set<(change: AuthChange) => void>()

function connection(): MessagePort {
  if (unavailable) throw new ApiError(0, 'AUTH_UNAVAILABLE', '登录协调暂不可用，请关闭本版旧标签页后重新打开。')
  if (port) return port
  if (!edition || typeof SharedWorker === 'undefined') {
    throw new ApiError(0, 'AUTH_BROWSER_UNSUPPORTED', '当前浏览器不支持安全登录，请使用新版桌面浏览器重新登录。')
  }
  const worker = new SharedWorker(new URL('./auth.worker.ts', import.meta.url), { type: 'module', name: 'c2-auth-' + editionCode })
  port = worker.port
  port.onmessage = (event: MessageEvent<AuthReply | AuthEvent>) => {
    if ('change' in event.data) {
      loginId = event.data.change.loginId
      for (const listener of listeners) listener(event.data.change)
      return
    }
    const reply = event.data
    const request = pending.get(reply.id)
    if (!request) return
    pending.delete(reply.id)
    if (reply.error) request.reject(new ApiError(reply.error.status, reply.error.code, reply.error.message))
    else { loginId = reply.loginId; request.resolve(reply.value) }
  }
  worker.onerror = () => {
    unavailable = true
    for (const request of pending.values()) request.reject(new ApiError(0, 'AUTH_UNAVAILABLE', '登录协调暂不可用，请关闭本版旧标签页后重新打开。'))
    pending.clear()
  }
  port.start()
  return port
}

function command<T>(command: AuthCommand['command'], extra: Partial<AuthCommand> = {}): Promise<T> {
  const channel = connection()
  const id = Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('')
  return new Promise<T>((resolve, reject) => {
    pending.set(id, { resolve: value => resolve(value as T), reject })
    channel.postMessage({ ...extra, id, edition, command })
  })
}

export const currentLoginId = () => loginId
export const accessLease = (expiredToken?: string, expectedLoginId = loginId) =>
  command<AccessLease>('access', { expiredToken, expectedLoginId })
export const login = (username: string, password: string) => command<Session>('login', { username, password })
export const logout = () => command<void>('logout', { expectedLoginId: loginId })
export const rejectIdentity = (expectedLoginId: string) => command<void>('reject', { expectedLoginId })
export function onAuthChange(listener: (change: AuthChange) => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

addEventListener('pagehide', event => {
  if (!(event as PageTransitionEvent).persisted && port) {
    port.postMessage({ command: 'disconnect' })
    port.close()
  }
})
