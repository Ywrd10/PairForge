import { useState } from 'react'

export function RoomInvitation({ roomId, token }: { roomId: string; token: string }) {
  const [message, setMessage] = useState('')
  return <section className="invitation" aria-label="Room invitation">
    <h3>Invite a teammate</h3>
    <p>Copy and save this invitation. It is available in this tab until you reload or log out.
      Share it privately only with people you want to admit. Your teammate must register and log in,
      then paste both fields into “Have an invitation?” on their dashboard.</p>
    <label htmlFor="created-room-id">Room ID</label>
    <input id="created-room-id" readOnly value={roomId} autoComplete="off" />
    <label htmlFor="created-token">Invitation token</label>
    <input id="created-token" readOnly value={token} autoComplete="off" spellCheck={false} />
    <button type="button" className="secondary" onClick={() => {
      void (async () => {
        try {
          await navigator.clipboard.writeText(`Room ID: ${roomId}\nInvitation token: ${token}`)
          setMessage('Invitation copied.')
        } catch { setMessage('Copy was unavailable. Select and copy the fields above.') }
      })()
    }}>Copy invitation</button>
    {message && <p role="status">{message}</p>}
  </section>
}
