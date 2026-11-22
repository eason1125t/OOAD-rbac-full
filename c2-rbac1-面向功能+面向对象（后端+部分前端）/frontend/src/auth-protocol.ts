// 文件职责：声明页面与同源认证SharedWorker之间的内存消息契约。
// 协作关系：auth.ts与auth.worker.ts共用；不写Web Storage，不承载权限快照。
import type { Session } from './api'

export interface AccessTokenReply { accessToken: string; tokenType: 'Bearer'; expiresIn: number }
export interface AccessLease { accessToken: string; loginId: string }
export type AuthChange = { kind: 'login' | 'logout' | 'invalid'; loginId?: string }
export interface AuthCommand {
  id: string
  edition: '/func' | '/oo'
  command: 'access' | 'login' | 'logout' | 'reject' | 'disconnect'
  username?: string
  password?: string
  expiredToken?: string
  expectedLoginId?: string
}
export interface AuthReply {
  id: string
  value?: Session | AccessLease
  loginId?: string
  error?: { status: number; code: string; message: string }
}
export interface AuthEvent { change: AuthChange }
