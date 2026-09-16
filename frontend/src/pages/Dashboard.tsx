import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError } from '../api/client'
import { createRoom, joinRoom, listRooms } from '../api/rooms'
import type { CreatedRoom, Language, RoomPage } from '../api/rooms'
import { useSession } from '../auth/context'
import { RequestError } from '../components/RequestError'
import { useAction } from '../components/useAction'

function Invitation({ created }: { created: CreatedRoom }) {
  const [message, setMessage] = useState('')
  return <section className="invitation" aria-label="Room invitation">
    <h3>Your room is ready</h3>
    <p>Copy and save this invitation now. It cannot be retrieved after leaving this page or logging out.
      Share it only with people you want to admit.</p>
    <label htmlFor="created-room-id">Room ID</label>
    <input id="created-room-id" readOnly value={created.room.id} />
    <label htmlFor="created-token">Invitation token</label>
    <input id="created-token" readOnly value={created.invitationToken} autoComplete="off" />
    <div className="actions">
      <button type="button" className="secondary" onClick={() => {
        void (async () => {
          try {
            await navigator.clipboard.writeText(`Room ID: ${created.room.id}\nInvitation token: ${created.invitationToken}`)
            setMessage('Invitation copied.')
          } catch { setMessage('Copy was unavailable. Select and copy the fields above.') }
        })()
      }}>Copy invitation</button>
      <Link to={`/rooms/${created.room.id}`}>Open room</Link>
    </div>
    {message && <p role="status">{message}</p>}
  </section>
}

function CreateRoom({ onCreated }: { onCreated: () => void }) {
  const { session } = useSession()
  const [name, setName] = useState('')
  const [language, setLanguage] = useState<Language>('JAVA')
  const [created, setCreated] = useState<CreatedRoom | null>(null)
  const { run, error, pending } = useAction()
  return <section className="panel">
    <h2>Create a room</h2>
    {created ? <><Invitation created={created} /><button className="secondary" type="button"
      onClick={() => { setCreated(null); setName('') }}>I saved the invitation — create another room</button></> :
      <form onSubmit={event => {
        event.preventDefault()
        void run(async signal => {
          if (!name.trim()) throw new ApiError('Enter a room name.', 400)
          const result = await createRoom(session, name, language, signal)
          signal.throwIfAborted()
          setCreated(result)
          onCreated()
        })
      }}>
        <label htmlFor="room-name">Room name</label>
        <input id="room-name" required maxLength={120} value={name} onChange={event => setName(event.target.value)} />
        <label htmlFor="language">Default language</label>
        <select id="language" value={language} onChange={event => setLanguage(event.target.value as Language)}>
          <option value="JAVA">Java</option><option value="PYTHON">Python</option>
        </select>
        <RequestError error={error} />
        {error instanceof ApiError && (error.status === 0 || error.status >= 500 || error.status === 201) &&
          <p role="status">The room may have been created. Refresh your room list before trying again.
            Retrying may create another room. A lost invitation cannot be recovered.</p>}
        <button disabled={pending}>{pending ? 'Creating…' : 'Create room'}</button>
      </form>}
  </section>
}

function JoinRoom() {
  const { session } = useSession()
  const navigate = useNavigate()
  const [id, setId] = useState('')
  const [token, setToken] = useState('')
  const { run, error, pending } = useAction()
  return <section className="panel">
    <h2>Have an invitation?</h2>
    <form onSubmit={event => {
      event.preventDefault()
      void run(async signal => {
        const result = await joinRoom(session, id.trim(), token.trim(), signal)
        signal.throwIfAborted()
        setToken('')
        navigate(`/rooms/${result.id}`)
      })
    }}>
      <label htmlFor="join-id">Room ID to join</label>
      <input id="join-id" required pattern="[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"
        value={id} onChange={event => setId(event.target.value)} />
      <label htmlFor="join-token">Invitation token to join</label>
      <input id="join-token" required pattern="[A-Za-z0-9_\-]{43}" autoComplete="off" type="password"
        value={token} onChange={event => setToken(event.target.value)} />
      <RequestError error={error} />
      <button disabled={pending}>{pending ? 'Joining…' : 'Join room'}</button>
    </form>
  </section>
}

function Rooms({ page, onPage }: { page: number; onPage: (page: number) => void }) {
  const { session } = useSession()
  const [data, setData] = useState<RoomPage | null>(null)
  const [error, setError] = useState<unknown>(null)
  useEffect(() => {
    const controller = new AbortController()
    void listRooms(session, page, controller.signal).then(result => {
      if (!controller.signal.aborted) setData(result)
    }).catch(error => { if (!controller.signal.aborted) setError(error) })
    return () => controller.abort()
  }, [session, page])
  return <>
    <RequestError error={error} />
    {!data && !error && <p role="status">Loading rooms…</p>}
    {data && (data.items.length === 0 ? <p>No rooms on this page. Create one or join with an invitation.</p> :
      <ul className="room-list">{data.items.map(room => <li key={room.id}>
        <Link to={`/rooms/${room.id}`}>{room.name}</Link>
        <span>{room.language === 'JAVA' ? 'Java' : 'Python'}</span>
      </li>)}</ul>)}
    <div className="actions" aria-label="Room pages">
      <button className="secondary" disabled={page === 0} onClick={() => onPage(page - 1)}>Previous</button>
      <span>Page {page + 1}</span>
      <button className="secondary" disabled={!data?.hasNext} onClick={() => onPage(page + 1)}>Next</button>
    </div>
  </>
}

export function Dashboard() {
  const [page, setPage] = useState(0)
  const [refresh, setRefresh] = useState(0)
  return <main>
    <p className="eyebrow">Make room for your next idea</p><h1>Your workspace</h1>
    <p>Create a room or pick up where you left off.</p>
    <section className="panel">
      <div className="section-title"><h2>Your rooms</h2><button className="secondary" onClick={() => setRefresh(value => value + 1)}>Refresh rooms</button></div>
      <Rooms key={`${page}-${refresh}`} page={page} onPage={setPage} />
    </section>
    <div className="columns">
      <CreateRoom onCreated={() => { setPage(0); setRefresh(value => value + 1) }} />
      <JoinRoom />
    </div>
  </main>
}
