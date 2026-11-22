// 文件职责：统一JSON请求、网络错误和API错误；不恢复身份或自动重试。
// 协作关系：api.ts发送Bearer业务请求，认证SharedWorker发送Cookie入口请求。
export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) { super(message) }
}

/** 原始请求一次完成；网络/未知执行结果绝不透明重发。 */
export async function rawRequest<T>(url: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  const timeout = new AbortController()
  const timer = setTimeout(() => timeout.abort(), 15000)
  try { response = await fetch(url, { ...options, signal: options.signal ?? timeout.signal, credentials: 'same-origin' }) }
  catch { throw new ApiError(0, 'NETWORK_ERROR', '无法连接后端，请检查服务后重试。') }
  finally { clearTimeout(timer) }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiError(response.status, body?.code ?? 'UNKNOWN_ERROR', body?.message ?? '请求失败，请重试。')
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>
}
