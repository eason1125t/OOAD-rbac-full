// 文件职责：在同一浏览器同一版的标签页间串行管理Cookie登录/刷新/退出与内存JWT。
// 协作关系：页面通过auth.ts消息调用；业务仍由api.ts携带Bearer并交后端实际授权。
import { ApiError, rawRequest } from './http'
import type { AccessLease, AccessTokenReply, AuthCommand, AuthReply, AuthChange } from './auth-protocol'
import type { Session } from './api'

const ports = new Set<MessagePort>()
let edition: '/func' | '/oo' | undefined
let access: (AccessLease & { expiresAt: number }) | undefined
let refreshFailure: ApiError | undefined
let queue = Promise.resolve()

function notify(change: AuthChange, except?: MessagePort) {
  for (const port of ports) if (port !== except) {
    try { port.postMessage({ change }) } catch { ports.delete(port) }
  }
}

/** 这里只读取sid用于请求归属保护；身份和权限仍完全由服务器验证。 */
function accept(reply: AccessTokenReply) {
  if (reply.tokenType !== 'Bearer' || !reply.accessToken || reply.expiresIn <= 0 || reply.expiresIn > 900) {
    throw new ApiError(502, 'AUTH_RESPONSE_INVALID', '登录响应无效，请重新登录。')
  }
  let sid: unknown
  try { sid = JSON.parse(atob(reply.accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sid }
  catch { throw new ApiError(502, 'AUTH_RESPONSE_INVALID', '登录响应无效，请重新登录。') }
  if (typeof sid !== 'string' || !sid) throw new ApiError(502, 'AUTH_RESPONSE_INVALID', '登录响应无效，请重新登录。')
  access = { accessToken: reply.accessToken, loginId: sid, expiresAt: Date.now() + reply.expiresIn * 1000 }
  refreshFailure = undefined
}

function reject(loginId?: string, kind: AuthChange['kind'] = 'invalid') {
  if (loginId && access && access.loginId !== loginId) return
  const hadIdentity = !!access
  access = undefined
  refreshFailure = new ApiError(401, 'AUTHENTICATION_REQUIRED', '请登录后重试')
  if (hadIdentity || kind === 'logout') notify({ kind })
}

async function cookieRequest<T>(path: string, body?: object): Promise<T> {
  const { token } = await rawRequest<{ token: string }>(edition + '/api/auth/csrf')
  const headers = new Headers({ 'X-CSRF-TOKEN': token })
  if (body) headers.set('Content-Type', 'application/json')
  return rawRequest<T>(edition + path, { method: 'POST', headers, body: body ? JSON.stringify(body) : undefined })
}

/** 所有标签页进入同一队列；后来的到期请求复用已更新JWT，不重复消费刷新凭证。 */
async function execute(message: AuthCommand, owner: MessagePort): Promise<Session | AccessLease | undefined> {
  if (message.command === 'reject') { reject(message.expectedLoginId); return }
  if (message.command === 'login') {
    const reply = await cookieRequest<Session & AccessTokenReply>('/api/auth/login',
      { username: message.username, password: message.password })
    accept(reply)
    notify({ kind: 'login', loginId: access!.loginId }, owner)
    return { employee: reply.employee, workspaces: reply.workspaces, defaultWorkspaceKey: reply.defaultWorkspaceKey }
  }
  if (message.expectedLoginId && access && access.loginId !== message.expectedLoginId) {
    throw new ApiError(409, 'AUTH_CONTEXT_CHANGED', '登录身份已变化，请重新操作。')
  }
  if (message.command === 'logout') {
    await cookieRequest<void>('/api/auth/logout')
    reject(undefined, 'logout')
    return
  }
  if (message.command !== 'access') throw new ApiError(400, 'AUTH_COMMAND_INVALID', '登录请求无效。')
  if (access && access.expiresAt > Date.now() + 1000 && access.accessToken !== message.expiredToken) return access
  if (refreshFailure) throw refreshFailure
  try {
    accept(await cookieRequest<AccessTokenReply>('/api/auth/refresh'))
    return access!
  } catch (failure) {
    const error = failure instanceof ApiError ? failure
      : new ApiError(0, 'AUTH_REFRESH_UNCERTAIN', '登录恢复未完成，请重新登录。')
    // 结果不确定或503不自动重试，也不把暂时存储故障当成已退出。
    refreshFailure = error
    if (error.status === 401) reject()
    throw error
  }
}

const scope = globalThis as unknown as { onconnect: (event: MessageEvent) => void }
scope.onconnect = event => {
  const port = event.ports[0]
  ports.add(port)
  port.onmessage = (event: MessageEvent<AuthCommand>) => {
    const message = event.data
    if (message?.command === 'disconnect') { ports.delete(port); port.close(); return }
    if (!message || typeof message.id !== 'string') return
    const run = async () => {
      let reply: AuthReply
      try {
        if (!['/func', '/oo'].includes(message.edition) || (edition && edition !== message.edition)) {
          throw new ApiError(400, 'AUTH_CONTEXT_CHANGED', '登录入口不匹配。')
        }
        edition = message.edition
        reply = { id: message.id, value: await execute(message, port), loginId: access?.loginId }
      } catch (failure) {
        const error = failure instanceof ApiError ? failure : new ApiError(0, 'AUTH_UNAVAILABLE', '登录服务暂不可用。')
        reply = { id: message.id, error: { status: error.status, code: error.code, message: error.message } }
      }
      try { port.postMessage(reply) } catch { ports.delete(port) }
    }
    queue = queue.then(run, run)
  }
  port.start()
}
