import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { getRoom } from '../api/rooms'
import type { Room } from '../api/rooms'
import { useSession } from '../auth/context'
import { RequestError } from '../components/RequestError'
import { ApiError } from '../api/client'
import { RoomWorkspace } from '../editor/RoomWorkspace'

function RoomDetails({ id }: { id: string }) {
  const { session } = useSession()
  const [room, setRoom] = useState<Room | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [refresh, setRefresh] = useState(0)
  const [loading, setLoading] = useState(true)
  useEffect(() => {
    const controller = new AbortController()
    void getRoom(session, id, controller.signal).then(result => {
      if (!controller.signal.aborted) setRoom(result)
    }).catch(error => {
      if (controller.signal.aborted) return
      setError(error)
      if (error instanceof ApiError && [401, 403, 404].includes(error.status)) setRoom(null)
    }).finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [session, id, refresh])
  return <>
    <div className="actions"><Link to="/dashboard">Back to dashboard</Link>
      <button className="secondary" disabled={loading} onClick={() => {
        setLoading(true); setError(null); setRefresh(value => value + 1)
      }}>{loading && room ? 'Refreshing room…' : 'Refresh room'}</button></div>
    <RequestError error={error} />
    {!room && !error && <p role="status">Loading room…</p>}
    {room && <section className="panel">
      <h1>{room.name}</h1>
      <dl><dt>Room ID</dt><dd>{room.id}</dd>
        <dt>Default language</dt><dd>{room.language === 'JAVA' ? 'Java' : 'Python'}</dd>
        <dt>Created</dt><dd>{new Date(room.createdAt).toLocaleString()}</dd></dl>
    </section>}
    {room && <RoomWorkspace defaultLanguage={room.language} />}
  </>
}

export function RoomOverview() {
  const { roomId = '' } = useParams()
  return <main>
    <RoomDetails key={roomId} id={roomId} />
  </main>
}
