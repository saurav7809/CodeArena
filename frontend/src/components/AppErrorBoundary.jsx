import { RefreshCw } from 'lucide-react';
import { Component } from 'react';

export default class AppErrorBoundary extends Component {
  state = { failed: false };

  static getDerivedStateFromError() { return { failed: true }; }

  render() {
    if (!this.state.failed) return this.props.children;
    return <main className="container page">
      <section className="panel padded form-stack" role="alert">
        <h1>This page couldn’t load</h1>
        <p>Reload the page to continue.</p>
        <button className="btn primary" type="button" onClick={() => window.location.reload()}><RefreshCw size={16} />Reload</button>
      </section>
    </main>;
  }
}
