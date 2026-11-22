// 文件职责：声明公开响应并统一发送Bearer业务请求，只有确定的访问过期才重试一次。
// 协作关系：auth.ts管理共享内存凭证，http.ts只发送一次；前端权限展示不替代后端授权。
import { accessLease, currentLoginId, rejectIdentity } from './auth'
import { ApiError, rawRequest } from './http'
export { ApiError } from './http'
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
  authorization?: CurrentAuthorization
}

export interface CurrentRole extends NamedItem {
  direct: boolean
  inherited: boolean
  inheritedFromRoleIds: number[]
  appliesToSystem: boolean
}
export interface CurrentAuthorization {
  systemCode: string
  roles: CurrentRole[]
  effectivePermissions: (NamedItem & { code: string })[]
  globalAdministrator: boolean
}
export interface PermissionCheck { systemCode: string; permissionCode: string; allowed: boolean }
export interface ApiCheck { systemCode: string; operationId: string; allowed: boolean }
export interface DepartmentNode extends NamedItem {
  code: string
  organizationType: 'COMPANY' | 'DEPARTMENT'
  parentId: number | null
  hasChildren: boolean
}
export interface DirectoryEmployee extends NamedItem { username: string; organizationId: number | null }
export interface EmployeeProfile extends Employee {
  jobTitle: string | null
  phone: string | null
  sourceBranchName: string | null
  sourceDepartmentName: string | null
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

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : '请求失败，请重试。'
}

const editionCode = /^\/(oo|func)(?:\/|$)/.exec(location.pathname)?.[1]
export const edition = editionCode ? '/' + editionCode : ''
export const editionName = editionCode === 'func' ? '面向功能版' : '面向对象版'
export const editionHome = `${edition}/`
export const workspacePath = (path: string) => `${edition}${path}`

/** 每次捕获登录归属；账户切换后的旧响应不得写回新页面或用新账户重放旧写入。 */
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const lease = await accessLease()
  const send = (accessToken: string) => {
    const headers = new Headers(options.headers)
    headers.set('Authorization', 'Bearer ' + accessToken)
    return rawRequest<T>(workspacePath(path), { ...options, headers })
  }
  const unchanged = () => {
    if (currentLoginId() !== lease.loginId) throw new ApiError(409, 'AUTH_CONTEXT_CHANGED', '登录身份已变化，请重新操作。')
  }
  try {
    const value = await send(lease.accessToken)
    unchanged()
    return value
  } catch (failure) {
    unchanged()
    if (failure instanceof ApiError && failure.status === 401 && failure.code === 'ACCESS_TOKEN_EXPIRED') {
      const fresh = await accessLease(lease.accessToken, lease.loginId)
      unchanged()
      try {
        const value = await send(fresh.accessToken)
        unchanged()
        return value
      } catch (retryFailure) {
        unchanged()
        if (retryFailure instanceof ApiError && retryFailure.status === 401) await rejectIdentity(lease.loginId)
        throw retryFailure
      }
    }
    if (failure instanceof ApiError && failure.status === 401) await rejectIdentity(lease.loginId)
    throw failure
  }
}

export const session = (systemCode?: string) => request<Session>('/api/auth/me'
  + (systemCode ? '?' + new URLSearchParams({ systemCode }) : ''))
export const checkPermission = (systemCode: string, permissionCode: string) =>
  request<PermissionCheck>('/api/auth/me/permission-check?' + new URLSearchParams({ systemCode, permissionCode }))
export const checkApi = (systemCode: string, operationId: string) =>
  request<ApiCheck>('/api/auth/me/api-check?' + new URLSearchParams({ systemCode, operationId }))
export const read = <T>(path: string) => request<T>(path)
export const list = <T>(path: string, page = 1, pageSize = 20) =>
  read<Page<T>>(`${path}${path.includes('?') ? '&' : '?'}page=${page}&pageSize=${pageSize}`)

/** Bearer业务写入不领取CSRF；网络错误和未知执行结果不自动重发。 */
export function write<T>(method: 'POST' | 'PUT' | 'PATCH' | 'DELETE', path: string, body?: object): Promise<T> {
  const headers = new Headers()
  if (body) headers.set('Content-Type', 'application/json')
  return request<T>(path, { method, headers, body: body ? JSON.stringify(body) : undefined })
}
