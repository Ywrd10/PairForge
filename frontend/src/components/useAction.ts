import { useEffect, useRef, useState } from 'react'

// One in-flight mutation per form; unmount cancels delivery, not server commits.
export function useAction() {
  const active = useRef<AbortController | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<unknown>(null)
  useEffect(() => () => { active.current?.abort() }, [])
  async function run(action: (signal: AbortSignal) => Promise<void>) {
    if (active.current && !active.current.signal.aborted) return
    const controller = new AbortController()
    active.current = controller
    setPending(true)
    setError(null)
    try { await action(controller.signal) }
    catch (error) { if (!controller.signal.aborted) setError(error) }
    finally {
      if (!controller.signal.aborted) {
        active.current = null
        setPending(false)
      }
    }
  }
  return { pending, error, run }
}
