import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import AppErrorBoundary from './components/AppErrorBoundary';
import { Providers } from './context';
import './styles.css';

createRoot(document.getElementById('root')).render(
  <AppErrorBoundary><BrowserRouter><Providers><App /></Providers></BrowserRouter></AppErrorBoundary>
);
