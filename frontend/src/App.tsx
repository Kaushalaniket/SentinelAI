import { BrowserRouter, Route, Routes } from 'react-router-dom'
import Shell from './components/Shell'
import Alerts from './pages/Alerts'
import Dashboard from './pages/Dashboard'
import Detection from './pages/Detection'
import Intel from './pages/Intel'
import LiveMonitor from './pages/LiveMonitor'
import NotFound from './pages/NotFound'
import Reports from './pages/Reports'
import Settings from './pages/Settings'
import Traffic from './pages/Traffic'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Shell />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/live" element={<LiveMonitor />} />
          <Route path="/traffic" element={<Traffic />} />
          <Route path="/detection" element={<Detection />} />
          <Route path="/intel" element={<Intel />} />
          <Route path="/alerts" element={<Alerts />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
