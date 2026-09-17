import { useEffect, useRef, useState } from 'react'
import type { EditorHandle, EditorLanguage } from './monaco'

export function CodeEditor({ initialSource, language, content, readOnly = false, onChange }:
  { initialSource: string; language: EditorLanguage; content?: string; readOnly?: boolean; onChange?: (source: string) => void }) {
  const host = useRef<HTMLDivElement>(null)
  const handle = useRef<EditorHandle | null>(null)
  const module = useRef<Promise<typeof import('./monaco')> | null>(null)
  const draft = useRef(initialSource)
  const currentLanguage = useRef(language)
  const change = useRef(onChange)
  const locked = useRef(readOnly)
  const [attempt, setAttempt] = useState(0)
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading')

  useEffect(() => {
    currentLanguage.current = language
    handle.current?.setLanguage(language)
  }, [language])
  useEffect(() => { change.current = onChange }, [onChange])
  useEffect(() => { locked.current = readOnly; handle.current?.setReadOnly(readOnly) }, [readOnly])
  useEffect(() => {
    if (content !== undefined) { draft.current = content; handle.current?.setContent(content) }
  }, [content])

  useEffect(() => {
    let cancelled = false
    // Reuse an in-flight load across StrictMode's effect cleanup/restart.
    module.current ??= import('./monaco')
    void module.current.then(({ createCodeEditor }) => {
      if (cancelled || !host.current) return
      handle.current = createCodeEditor(host.current, draft.current, currentLanguage.current,
        source => { draft.current = source; change.current?.(source) })
      handle.current.setReadOnly(locked.current)
      setStatus('ready')
    }).catch(() => {
      if (!cancelled) { module.current = null; setStatus('failed') }
    })
    return () => {
      cancelled = true
      handle.current?.dispose()
      handle.current = null
    }
  }, [attempt])

  return <div className="code-editor">
    {status === 'loading' && <p role="status">Loading editor…</p>}
    {status === 'failed' && <div className="error" role="alert">
      <p>The editor could not load. Check your connection and try again.</p>
      <p>If retry fails, reload this page. Reloading discards your draft and requires a new login.</p>
      <button className="secondary" onClick={() => { setStatus('loading'); setAttempt(value => value + 1) }}>Retry editor</button>
    </div>}
    <div className="editor-host" ref={host} hidden={status === 'failed'} />
  </div>
}
