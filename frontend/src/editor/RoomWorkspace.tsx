import { useState } from 'react'
import { CodeEditor } from './CodeEditor'
import type { EditorLanguage } from './monaco'

const templates: Record<EditorLanguage, string> = {
  JAVA: 'public class Main {\n    public static void main(String[] args) {\n        System.out.println("Hello, PairForge!");\n    }\n}\n',
  PYTHON: 'print("Hello, PairForge!")\n',
}

export function RoomWorkspace({ defaultLanguage }: { defaultLanguage: EditorLanguage }) {
  const [language, setLanguage] = useState(defaultLanguage)
  const [initialSource] = useState(() => templates[defaultLanguage])
  return <>
    <section className="panel" aria-label="Code editor">
      <div className="editor-toolbar">
        <div><h2>{language === 'JAVA' ? 'Main.java' : 'main.py'}</h2><span>Temporary draft</span></div>
        <label>Editor language
          <select value={language} onChange={event => setLanguage(event.target.value === 'JAVA' ? 'JAVA' : 'PYTHON')}>
            <option value="JAVA">Java</option><option value="PYTHON">Python</option>
          </select>
        </label>
        <button disabled aria-describedby="execution-notice">Run</button>
      </div>
      <p>Edits stay in this page only. They are not saved or shared and are discarded when you leave, reload, or log out.
        Switching language keeps your source text.</p>
      <CodeEditor initialSource={initialSource} language={language} />
    </section>
    <section className="panel" aria-label="Connection status">
      <h2>Connection status</h2><p>Local editing only — collaboration is not connected.</p>
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
