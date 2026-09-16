import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { register } from '../api/auth'
import { ApiError } from '../api/client'
import { useSession } from '../auth/context'
import { RequestError } from '../components/RequestError'
import { useAction } from '../components/useAction'

export function AuthPage({ registration = false }: { registration?: boolean }) {
  const { session, user, expired } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const { run, error, pending } = useAction()
  const destination: unknown = location.state?.from
  const from = typeof destination === 'string' && /^\/(dashboard|rooms\/[a-f0-9-]{36})$/.test(destination)
    ? destination : '/dashboard'
  if (user) return <Navigate to={from} replace />
  return <main className="auth-page">
    <p className="eyebrow">Your next idea starts here</p>
    <h1>{registration ? 'Create an account' : 'Welcome back'}</h1>
    <p>{registration ? 'Make a space to code together.' : 'Log in to open your rooms.'}</p>
    {!registration && expired && <p role="status">Your session expired. Please log in again.</p>}
    {!registration && location.state?.registered && <p role="status">Account created. Log in to continue.</p>}
    <form onSubmit={event => {
      event.preventDefault()
      void run(async signal => {
        if (registration && ([...password].length < 15 || new TextEncoder().encode(password).length > 72)) {
          throw new ApiError('Use at least 15 characters and no more than 72 UTF-8 bytes for your password.')
        }
        if (registration) {
          await register(email, password, signal)
          signal.throwIfAborted()
          setPassword('')
          navigate('/login', { replace: true, state: { registered: true, from } })
        } else {
          await session.login(email, password, signal)
          signal.throwIfAborted()
          setPassword('')
          navigate(from, { replace: true })
        }
      })
    }}>
      <label htmlFor="email">Email</label>
      <input id="email" type="email" autoComplete="username" required maxLength={254}
        value={email} onChange={event => setEmail(event.target.value)} />
      <label htmlFor="password">Password</label>
      <input id="password" type="password" autoComplete={registration ? 'new-password' : 'current-password'}
        required value={password} onChange={event => setPassword(event.target.value)}
        aria-describedby={registration ? 'password-help' : undefined} />
      {registration && <small id="password-help">At least 15 characters; at most 72 UTF-8 bytes. Spaces are preserved.</small>}
      <RequestError error={error} />
      <button disabled={pending}>{pending ? 'Please wait…' : registration ? 'Create account' : 'Log in'}</button>
    </form>
    <p>{registration ? 'Already have an account?' : 'New to PairForge?'}{' '}
      <Link to={registration ? '/login' : '/register'} state={{ from }}>
        {registration ? 'Log in' : 'Create an account'}
      </Link>
    </p>
    <small>For your privacy, refreshing this page requires a new login.</small>
  </main>
}
