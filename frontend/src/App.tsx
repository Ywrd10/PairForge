import { Link, Navigate, Outlet, Route, Routes, useLocation } from 'react-router'
import { useSession } from './auth/context'
import { AuthPage } from './pages/AuthPage'
import { Dashboard } from './pages/Dashboard'
import { RoomOverview } from './pages/RoomOverview'

function Protected() {
  const { user } = useSession()
  const location = useLocation()
  return user ? <Outlet key={user.id} /> : <Navigate to="/login" replace state={{ from: location.pathname }} />
}

export default function App() {
  const { user, session } = useSession()
  return <>
    <header className="site-header">
      <Link className="brand" to={user ? '/dashboard' : '/login'}>PairForge<span aria-hidden="true"> /</span></Link>
      {user && <nav aria-label="Account"><span className="account-email">{user.email}</span>
        <button className="secondary" onClick={() => session.logout()}>Log out</button></nav>}
    </header>
    <Routes>
      <Route path="/" element={<Navigate to={user ? '/dashboard' : '/login'} replace />} />
      <Route path="/login" element={<AuthPage key="login" />} />
      <Route path="/register" element={<AuthPage key="register" registration />} />
      <Route element={<Protected />}>
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/rooms/:roomId" element={<RoomOverview />} />
      </Route>
      <Route path="*" element={<main><h1>Page not found</h1><Link to="/">Return home</Link></main>} />
    </Routes>
    <footer>PairForge · A space to code together</footer>
  </>
}
