import { createContext, useContext, useSyncExternalStore } from 'react'
import { Session } from './session'

export const SessionContext = createContext<Session | null>(null)
export function useSession() {
  const session = useContext(SessionContext)
  if (!session) throw new Error('Session provider is required')
  const state = useSyncExternalStore(session.subscribe, session.getSnapshot)
  return { session, ...state }
}
