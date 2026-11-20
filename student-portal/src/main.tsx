import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import './redesign.css'
import './navigation.css'
import App from './App.tsx'
import { AuthProvider } from './auth/AuthProvider'

import { AppearanceProvider } from './appearance'
import './appearance.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppearanceProvider><AuthProvider><App /></AuthProvider></AppearanceProvider>
  </StrictMode>,
)
