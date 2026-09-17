import { useEffect, useState, useSyncExternalStore } from 'react'
import { CodeEditor } from './CodeEditor'
import type { EditorLanguage } from './monaco'
import { useSession } from '../auth/context'
import { CollaborationClient } from '../collaboration/client'

const templates: Record<EditorLanguage, string> = {
  JAVA: 'public class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, PairForge!");\n    }\n}\n',
  PYTHON: 'print("Hello, PairForge!")\n',
}

export function RoomWorkspace({ roomId, defaultLanguage }: { roomId: string; defaultLanguage: EditorLanguage }) {
  const { session } = useSession()
  const [initialSource] = useState(() => templates[defaultLanguage])
  const [client] = useState(() => new CollaborationClient(session, roomId, initialSource, defaultLanguage))
  const state = useSyncExternalStore(client.subscribe, client.getSnapshot)
  const { language } = state
  const [copyNotice, setCopyNotice] = useState<{ backup: typeof state.backup; message: string } | null>(null)
  useEffect(() => { client.start(); return () => client.stop() }, [client])
  return <>
    <section className="panel" aria-label="Code editor">
      <div className="editor-toolbar">
        <div><h2>{language === 'JAVA' ? 'Main.java' : 'main.py'}</h2><span>Shared room document</span></div>
        <label>Editor language
          <select value={language} disabled={!state.ready} onChange={event => client.edit(state.content, event.target.value === 'JAVA' ? 'JAVA' : 'PYTHON')}>
            <option value="JAVA">Java</option><option value="PYTHON">Python</option>
          </select>
        </label>
        <button disabled aria-describedby="execution-notice">Run</button>
      </div>
      <p>Accepted edits are shared through Redis and expire after 24 hours of inactivity. Simultaneous edits may overwrite each other.
        Unsynchronized edits stay in this page only; copy them before leaving. Switching language keeps your source text.</p>
      <CodeEditor initialSource={initialSource} language={language} content={state.content} readOnly={!state.ready}
        onChange={content => client.edit(content, language)} />
    </section>
    <section className="panel" aria-label="Connection status">
      <h2>Connection status</h2><p role="status">{state.notice}</p>
      {!state.connected && <>
        <p>Reconnect loads the current server document. Unsynchronized edits are kept as one local backup and are never sent automatically.</p>
        <button disabled={state.connecting} onClick={() => { setCopyNotice(null); client.start() }}>Reconnect</button>
      </>}
      {state.backup && <section aria-label="Unsynchronized draft backup">
        <h3>Local draft backup ({state.backup.language === 'JAVA' ? 'Main.java' : 'main.py'})</h3>
        <p>Copy before leaving: this backup exists only in this page. A later recovery replaces it with your latest unsynchronized draft.</p>
        <textarea className="draft-backup" rows={6} aria-label="Local draft backup" readOnly value={state.backup.content} />
        <div className="actions">
          <button className="secondary" onClick={async () => {
            const backup = state.backup!
            setCopyNotice(null)
            try {
              await navigator.clipboard.writeText(backup.content)
              if (client.getSnapshot().backup === backup) setCopyNotice({ backup, message: 'Draft copied.' })
            } catch {
              if (client.getSnapshot().backup === backup) setCopyNotice({ backup, message: 'Copy unavailable. Select the backup text and copy it manually.' })
            }
          }}>Copy draft</button>
          <button className="secondary" onClick={() => { client.clearBackup(); setCopyNotice(null) }}>Discard backup</button>
        </div>
        {copyNotice?.backup === state.backup && <p role="status">{copyNotice.message}</p>}
      </section>}
    </section>
    <section className="panel" aria-label="Output">
      <h2>Output</h2><p id="execution-notice">Execution is not available yet. Run is disabled.</p>
      <div className="output-columns">
        <section aria-label="stdout"><h3>stdout</h3><pre aria-label="Standard output">No output yet.</pre></section>
        <section aria-label="stderr"><h3>stderr</h3><pre aria-label="Standard error">No errors yet.</pre></section>
      </div>
    </section>
  </>
}
