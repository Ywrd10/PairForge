import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { getRoom } from '../api/rooms'
import type { Room } from '../api/rooms'
import { useSession } from '../auth/context'
import { RequestError } from '../components/RequestError'

function RoomDetails({ id }: { id: string }) {
  const { session } = useSession()
  const [room, setRoom] = useState<Room | null>(null)
  const [error, setError] = useState<unknown>(null)
  useEffect(() => {
    const controller = new AbortController()
    void getRoom(session, id, controller.signal).then(result => {
      if (!controller.signal.aborted) setRoom(result)
    }).catch(error => { if (!controller.signal.aborted) setError(error) })
    return () => controller.abort()
  }, [session, id])
  return <>
    <RequestError error={error} />
    {!room && !error && <p role="status">Loading room…</p>}
    {room && <section className="panel">
      <h1>{room.name}</h1>
      <dl><dt>Room ID</dt><dd>{room.id}</dd>
        <dt>Default language</dt><dd>{room.language === 'JAVA' ? 'Java' : 'Python'}</dd>
        <dt>Created</dt><dd>{new Date(room.createdAt).toLocaleString()}</dd></dl>
      <p>You have access to this room. The coding editor is coming soon.</p>
    </section>}
  </>
}

export function RoomOverview() {
  const { roomId = '' } = useParams()
  const [refresh, setRefresh] = useState(0)
  return <main>
    <div className="actions"><Link to="/dashboard">Back to dashboard</Link>
      <button className="secondary" onClick={() => setRefresh(value => value + 1)}>Refresh room</button></div>
    <RoomDetails key={`${roomId}-${refresh}`} id={roomId} />
  </main>
}
