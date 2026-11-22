// 文件职责：只在测试进程内观察页面真实发送的Bearer，供原有HTTP断言复用。
// 协作关系：不刷新Cookie、不提供生产调试入口；UI登录仍通过真实页面完成。
import type { APIRequestContext, APIResponse, Page } from '@playwright/test'

const credentials = new WeakMap<Page, Map<string, string>>()
type Options = NonNullable<Parameters<APIRequestContext['fetch']>[1]>

function editionOf(path: string) { return /^\/(func|oo)\//.exec(new URL(path, 'http://localhost').pathname)?.[1] }

export function trackAuth(page: Page) {
  if (credentials.has(page)) return
  credentials.set(page, new Map())
  page.on('request', request => {
    const edition = editionOf(request.url())
    const authorization = request.headers()['authorization']
    if (edition && authorization?.startsWith('Bearer ')) credentials.get(page)!.set(edition, authorization.slice(7))
  })
}

export function observedBearer(page: Page, edition = editionOf(page.url())) {
  const token = edition ? credentials.get(page)?.get(edition) : undefined
  if (!token) throw new Error('No observed browser Bearer for this test actor')
  return token
}

export async function apiFetch(page: Page, path: string, options: Options = {}): Promise<APIResponse> {
  const edition = editionOf(path)
  const cookieEndpoint = /\/api\/auth\/(csrf|login|refresh|logout)(?:\?|$)/.test(path)
  const headers = { ...options.headers }
  if (!cookieEndpoint && !headers.Authorization && !headers.authorization) {
    headers.Authorization = 'Bearer ' + observedBearer(page, edition)
  }
  const response = await page.request.fetch(path, { ...options, headers })
  if (/\/api\/auth\/login$/.test(path) && response.ok() && edition) {
    trackAuth(page)
    credentials.get(page)!.set(edition, (await response.json()).accessToken)
  }
  return response
}
export const apiGet = (page: Page, path: string) => apiFetch(page, path, { method: 'GET' })
export const apiPost = (page: Page, path: string, options: Options = {}) => apiFetch(page, path, { ...options, method: 'POST' })
