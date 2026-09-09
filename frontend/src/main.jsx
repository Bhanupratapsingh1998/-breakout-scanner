import { Component, StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App.jsx'

/** Catches render-time errors so a bug in one view shows a message instead of a silent blank
 *  page — e.g. a field the UI didn't expect (this is what happened with an "Infinity" ratio). */
class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    console.error('Unhandled render error:', error, info)
  }

  render() {
    if (this.state.error) {
      return (
        <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24, fontFamily: 'system-ui, sans-serif' }}>
          <div style={{ maxWidth: 480, textAlign: 'center' }}>
            <p style={{ fontSize: 18, fontWeight: 600, marginBottom: 8 }}>Something went wrong</p>
            <p style={{ fontSize: 14, color: '#666', marginBottom: 16 }}>{String(this.state.error.message ?? this.state.error)}</p>
            <button
              onClick={() => { this.setState({ error: null }); window.location.reload() }}
              style={{ padding: '8px 16px', borderRadius: 8, background: '#2563eb', color: 'white', border: 'none', fontWeight: 600, cursor: 'pointer' }}
            >
              Reload
            </button>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <ErrorBoundary>
      <App />
    </ErrorBoundary>
  </StrictMode>,
)
