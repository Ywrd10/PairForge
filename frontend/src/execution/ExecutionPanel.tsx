import { useSyncExternalStore } from 'react'
import type { ExecutionClient } from './client'

export function ExecutionPanel({ client }: { client: ExecutionClient }) {
  const state = useSyncExternalStore(client.subscribe, client.getSnapshot)
  const result = state.detail
  return <section className="panel" aria-label="Output">
    <h2>Output</h2>
    <p id="execution-notice">Run submits the visible source and language. One file, standard libraries, closed stdin, no external network.</p>
    <label>Recent executions <select value={state.selected ?? ''} onChange={e => client.select(e.target.value)}>
      <option value="" disabled>No execution selected</option>
      {state.selected && !state.items.some(item => item.id === state.selected) && <option value={state.selected}>{state.selected}</option>}
      {state.items.map(item => <option key={item.id} value={item.id}>{item.language} · {item.status} · {item.id}</option>)}
    </select></label>
    <button className="secondary" disabled={state.refreshing} onClick={() => void client.refresh()}>Refresh Status</button>
    <p role="status" aria-label="Execution status">{state.submitting ? 'Submitting…' : result?.status ?? 'No result loaded.'}</p>
    {state.notice && <p role="alert">{state.notice}</p>}
    {state.refreshError && <p role="alert">{state.refreshError}</p>}
    {state.delayed && <p>Status updates are delayed. Use Refresh Status; do not assume the execution failed.</p>}
    {result && <p>Execution {result.id} · Exit code: {result.exitCode ?? '—'} · Duration: {result.durationMs ?? '—'} ms
      {result.failureReason && ` · ${result.failureReason}`}{result.outputTruncated && ' · Output truncated at the combined limit.'}</p>}
    <div className="output-columns">
      <section aria-label="stdout"><h3>stdout</h3><pre aria-label="Standard output">{result?.stdout || 'No output yet.'}</pre></section>
      <section aria-label="stderr"><h3>stderr</h3><pre aria-label="Standard error">{result?.stderr || 'No errors yet.'}</pre></section>
    </div>
  </section>
}
