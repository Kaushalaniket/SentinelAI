import { Link } from 'react-router-dom'
import { EmptyState } from '../components/ui'

export default function NotFound() {
  return (
    <div className="space-y-5">
      <EmptyState
        icon="404"
        title="Page not found"
        description="That URL does not match any SentinelAI console page. Use the sidebar to get back to the dashboard."
        action={
          <Link to="/" className="btn btn-primary">
            Back to dashboard
          </Link>
        }
      />
    </div>
  )
}
