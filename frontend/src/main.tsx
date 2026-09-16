import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'
import { BrowserRouter } from 'react-router'
import { AuthProvider } from './auth/AuthProvider'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter><AuthProvider><App /></AuthProvider></BrowserRouter>
  </StrictMode>,
)
