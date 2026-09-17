import { editor, languages } from 'monaco-editor/editor/editor.api'
import 'monaco-editor/editor/browser/coreCommands'
import 'monaco-editor/editor/contrib/clipboard/browser/clipboard'
import 'monaco-editor/editor/contrib/bracketMatching/browser/bracketMatching'
import 'monaco-editor/editor/contrib/suggest/browser/suggestController'
import * as java from 'monaco-editor/languages/definitions/java/java'
import * as python from 'monaco-editor/languages/definitions/python/python'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'

export type EditorLanguage = 'JAVA' | 'PYTHON'
export interface EditorHandle {
  setLanguage(language: EditorLanguage): void
  setContent(content: string): void
  setReadOnly(readOnly: boolean): void
  dispose(): void
}

// Vite bundles the worker locally; no CDN, credentials, or application API access.
globalThis.MonacoEnvironment = { getWorker: () => new EditorWorker() }

// Load both definitions with this lazy module. A later language switch must not
// trigger Monaco's separate asynchronous loaders with uncaught download failures.
for (const [id, definition] of Object.entries({ java, python })) {
  languages.register({ id })
  languages.setLanguageConfiguration(id, definition.conf)
  languages.setMonarchTokensProvider(id, definition.language)
}

export function createCodeEditor(host: HTMLElement, source: string, language: EditorLanguage,
  onChange: (source: string) => void): EditorHandle {
  const model = editor.createModel(source, language.toLowerCase())
  try {
    const instance = editor.create(host, {
      model, theme: 'vs-dark', ariaLabel: 'Source code', automaticLayout: true,
      minimap: { enabled: false }, fontSize: 14, scrollBeyondLastLine: false,
      wordWrap: 'on', tabSize: 4, padding: { top: 12, bottom: 12 },
      wordBasedSuggestions: 'currentDocument',
    })
    let applyingRemote = false
    const listener = model.onDidChangeContent(() => { if (!applyingRemote) onChange(model.getValue()) })
    return {
      setLanguage: next => editor.setModelLanguage(model, next.toLowerCase()),
      setReadOnly: readOnly => instance.updateOptions({ readOnly }),
      setContent: content => {
        if (model.getValue() === content) return
        applyingRemote = true
        try {
          // Remote full replacements deliberately clear local undo history: undo
          // must not resurrect another participant's superseded document.
          const position = instance.getPosition()
          model.setValue(content)
          if (position) instance.setPosition(position)
        } finally { applyingRemote = false }
      },
      dispose: () => { listener.dispose(); instance.dispose(); model.dispose() },
    }
  } catch (error) {
    model.dispose()
    throw error
  }
}
