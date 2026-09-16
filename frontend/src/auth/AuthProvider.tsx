import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { SessionContext } from './context'
import { Session } from './session'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session] = useState(() => new Session())
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout>
    const schedule = () => {
      clearTimeout(timer)
      if (session.getSnapshot().user) timer = setTimeout(session.checkExpiry, session.millisecondsRemaining())
    }
    const unsubscribe = session.subscribe(schedule)
    schedule()
    window.addEventListener('focus', session.checkExpiry)
    document.addEventListener('visibilitychange', session.checkExpiry)
    return () => {
      clearTimeout(timer)
      unsubscribe()
      window.removeEventListener('focus', session.checkExpiry)
      document.removeEventListener('visibilitychange', session.checkExpiry)
    }
  }, [session])
  return <SessionContext.Provider value={session}>{children}</SessionContext.Provider>
}
