import { StrictMode } from 'react'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { CodeEditor } from './CodeEditor'
import { createCodeEditor } from './monaco'
import type { EditorLanguage } from './monaco'

vi.mock('./monaco', () => ({ createCodeEditor: vi.fn() }))
const create = vi.mocked(createCodeEditor)
let handle: { setLanguage: ReturnType<typeof vi.fn<(language: EditorLanguage) => void>>; dispose: ReturnType<typeof vi.fn<() => void>> }
beforeEach(() => {
  create.mockReset()
  handle = { setLanguage: vi.fn(), dispose: vi.fn() }
  create.mockReturnValue(handle)
})

it('creates one model, changes only its language, and disposes on unmount', async () => {
  const view = render(<CodeEditor initialSource="original source" language="JAVA" />)
  await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
  expect(create).toHaveBeenCalledWith(expect.any(HTMLElement), 'original source', 'JAVA', expect.any(Function))
  create.mock.calls[0][3]('edited source')
  view.rerender(<CodeEditor initialSource="replacement must be ignored" language="PYTHON" />)
  expect(handle.setLanguage).toHaveBeenLastCalledWith('PYTHON')
  expect(create).toHaveBeenCalledTimes(1)
  view.unmount()
  expect(handle.dispose).toHaveBeenCalledTimes(1)
})

it('shows initialization failure and lets the user retry', async () => {
  create.mockImplementationOnce(() => { throw new Error('Editor initialization failed') })
  render(<CodeEditor initialSource="source" language="JAVA" />)
  expect(await screen.findByRole('alert')).toHaveProperty('textContent', expect.stringContaining('could not load'))
  fireEvent.click(screen.getByRole('button', { name: 'Retry editor' }))
  await waitFor(() => expect(create).toHaveBeenCalledTimes(2))
  expect(screen.queryByRole('alert')).toBeNull()
})

it('does not initialize a late-loaded editor after unmount', async () => {
  const view = render(<CodeEditor initialSource="source" language="JAVA" />)
  view.unmount()
  await act(async () => { await import('./monaco') })
  expect(create).not.toHaveBeenCalled()
})

it('survives StrictMode cleanup and uses the latest language during loading', async () => {
  const view = render(<StrictMode><CodeEditor initialSource="source" language="JAVA" /></StrictMode>)
  view.rerender(<StrictMode><CodeEditor initialSource="source" language="PYTHON" /></StrictMode>)
  await act(async () => { await vi.dynamicImportSettled() })
  await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
  expect(create.mock.calls[0][2]).toBe('PYTHON')
  view.unmount()
  expect(handle.dispose).toHaveBeenCalledTimes(1)
})
