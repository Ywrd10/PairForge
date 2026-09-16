import type { Session } from '../auth/session'
import { expectResponse, isRecord } from './client'

export type Language = 'JAVA' | 'PYTHON'
export interface Room {
  id: string; ownerId: string; name: string; language: Language; createdAt: string; updatedAt: string
}
export interface CreatedRoom { room: Room; invitationToken: string }
export interface RoomPage { items: Room[]; page: number; size: number; hasNext: boolean }
function isRoom(value: unknown): value is Room {
  return isRecord(value) && typeof value.id === 'string' &&
    /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value.id) &&
    typeof value.ownerId === 'string' && typeof value.name === 'string' &&
    (value.language === 'JAVA' || value.language === 'PYTHON') &&
    typeof value.createdAt === 'string' && Number.isFinite(Date.parse(value.createdAt)) &&
    typeof value.updatedAt === 'string' && Number.isFinite(Date.parse(value.updatedAt))
}
function isPage(value: unknown): value is RoomPage {
  return isRecord(value) && Array.isArray(value.items) && value.items.every(isRoom) &&
    typeof value.page === 'number' && Number.isInteger(value.page) && value.page >= 0 &&
    typeof value.size === 'number' && Number.isInteger(value.size) && value.size >= 1 && value.size <= 100 &&
    typeof value.hasNext === 'boolean'
}
function isCreated(value: unknown): value is CreatedRoom {
  return isRecord(value) && isRoom(value.room) && typeof value.invitationToken === 'string' &&
    /^[A-Za-z0-9_-]{43}$/.test(value.invitationToken)
}
export const listRooms = async (session: Session, page: number, signal: AbortSignal) =>
  expectResponse(await session.request<unknown>(`/rooms?page=${page}&size=20`, { signal }), isPage)
export const getRoom = async (session: Session, id: string, signal: AbortSignal) =>
  expectResponse(await session.request<unknown>(`/rooms/${encodeURIComponent(id)}`, { signal }), isRoom)
export const createRoom = async (session: Session, name: string, language: Language, signal: AbortSignal) =>
  expectResponse(await session.request<unknown>('/rooms', { method: 'POST', body: { name, language }, signal }), isCreated)
export const joinRoom = async (session: Session, id: string, invitationToken: string, signal: AbortSignal) =>
  expectResponse(await session.request<unknown>(`/rooms/${encodeURIComponent(id)}/join`, {
    method: 'POST', body: { invitationToken }, signal,
  }), isRoom)
