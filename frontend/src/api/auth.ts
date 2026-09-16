import { expectResponse, isRecord, request } from './client'

export interface User { id: string; email: string }
export interface LoginResponse {
  accessToken: string; tokenType: string; expiresIn: number; expiresAt: string
}
function isUser(value: unknown): value is User {
  return isRecord(value) && typeof value.id === 'string' && value.id.length > 0 &&
    typeof value.email === 'string' && value.email.length > 0
}
function isLogin(value: unknown): value is LoginResponse {
  return isRecord(value) && typeof value.accessToken === 'string' && value.accessToken.length > 0 &&
    value.tokenType === 'Bearer' && typeof value.expiresIn === 'number' &&
    Number.isFinite(value.expiresIn) && value.expiresIn > 0 && typeof value.expiresAt === 'string'
}
export const register = async (email: string, password: string, signal: AbortSignal) =>
  expectResponse(await request<unknown>('/auth/register', { method: 'POST', body: { email, password }, signal }), isUser)
export const login = async (email: string, password: string, signal: AbortSignal) =>
  expectResponse(await request<unknown>('/auth/login', { method: 'POST', body: { email, password }, signal }), isLogin)
export const me = async (token: string, signal: AbortSignal) =>
  expectResponse(await request<unknown>('/auth/me', { token, signal }), isUser)
