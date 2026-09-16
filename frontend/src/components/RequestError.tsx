import { ApiError } from '../api/client'

export function RequestError({ error }: { error: unknown }) {
  if (!error) return null
  const api = error instanceof ApiError ? error : null
  return <div role="alert" className="error">
    <p>{api?.message ?? 'Something went wrong. Please try again.'}</p>
    {api && Object.entries(api.fieldErrors).map(([field, message]) => <p key={field}>{field}: {message}</p>)}
    {api?.retryAfter && <p>Try again after {api.retryAfter} seconds.</p>}
    {api?.requestId && <small>Request ID: {api.requestId}</small>}
  </div>
}
