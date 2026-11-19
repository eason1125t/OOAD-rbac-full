// 封装 OpenAPI 所需的响应字段和同源会话请求。
export interface Workspace {
  key: string
  type: 'ADMIN' | 'BUSINESS'
  name: string
  path: string
}

export interface Session {
  employee: { id: number; username: string; name: string }
  workspaces: Workspace[]
  defaultWorkspaceKey: string | null
}

export interface Page<T> {
  items: T[]
  page: number
  pageSize: number
  total: number
}

export interface NamedItem { id: number; name: string }
export interface Employee extends NamedItem { username: string; enabled: boolean; organizationId: number | null }
export interface Permission extends NamedItem { code: string; systemId: number | null }
export interface BusinessSystem extends NamedItem {
  code: string
  ownerOrganizationId: number
  ownerOrganizationName: string
}
export type RequestStatus = 'PENDING' | 'APPROVED' | 'WITHDRAWN' | 'REJECTED'
export type RequestAction = 'create' | 'update' | 'withdraw' | 'approve' | 'reject' | 'delete'
export interface RequestItem { id: number; title: string; status: RequestStatus }
export interface RequestDetail extends RequestItem { applicantId: number | null }
export interface RequestPage extends Page<RequestItem> { allowedActions: `request:${RequestAction}`[] }
export interface Role extends NamedItem { ownerOrganizationId: number | null }
export interface Approval { authorization: 'GRANTED'; execution: 'SUCCEEDED'; requestId: number; status: 'APPROVED' }

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string, message: string) {
    super(message)
  }
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请重试。'
}

const version = /^\/(oo|func)(?:\/|$)/.exec(location.pathname)?.[1]
export const edition = version ? '/' + version : ''
export const editionName = version === 'func' ? '面向功能版' : '面向对象版'
export const editionHome = `${edition}/`
export const workspacePath = (path: string) => `${edition}${path}`

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(workspacePath(path), { ...options, credentials: 'same-origin' })
  } catch {
    throw new Error('无法连接后端，请检查服务后重试。')
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null)
    throw new ApiError(response.status, body?.code ?? 'UNKNOWN_ERROR', body?.message ?? '请求失败，请重试。')
  }
  return response.status === 204 ? undefined as T : await response.json() as T
}

export const session = () => request<Session>('/api/auth/me')
export const read = <T>(path: string) => request<T>(path)
export const list = <T>(path: string, page = 1, pageSize = 20) =>
  read<Page<T>>(`${path}${path.includes('?') ? '&' : '?'}page=${page}&pageSize=${pageSize}`)

/** 每次写入使用当前 Cookie 领取 CSRF，覆盖登录时的 Cookie 轮换。 */
export async function write<T>(method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, body?: object): Promise<T> {
  const { token } = await request<{ token: string }>('/api/auth/csrf')
  const headers = new Headers({ 'X-CSRF-TOKEN': token })
  if (body) headers.set('Content-Type', 'application/json')
  return request<T>(path, { method, headers, body: body ? JSON.stringify(body) : undefined })
}
