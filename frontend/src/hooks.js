import { useCallback, useEffect, useRef, useState } from 'react';
import { api, message } from './api';

export function useResource(path) {
  const [state, setState] = useState({ path, data: null, error: '', loading: Boolean(path) });
  const [version, setVersion] = useState(0);
  const refresh = useCallback(() => setVersion((value) => value + 1), []);
  useEffect(() => {
    if (!path) return;
    const controller = new AbortController();
    setState((previous) => previous.path === path && previous.data
      ? { ...previous, error: '' } : { path, data: null, error: '', loading: true });
    api.get(path, { signal: controller.signal })
      .then(({ data }) => { if (!controller.signal.aborted) setState({ path, data, error: '', loading: false }); })
      .catch((error) => {
        if (!controller.signal.aborted) setState({ path, data: null, error: message(error), loading: false });
      });
    return () => controller.abort();
  }, [path, version]);
  return state.path === path ? { ...state, refresh } : { data: null, error: '', loading: Boolean(path), refresh };
}

export function useAiRequest(path) {
  const request = useRef(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => () => { request.current?.abort(); }, []);

  async function ask(payload) {
    if (request.current) return null;
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setError('');
    try {
      const { data } = await api.post(path, payload, { signal: controller.signal, timeout: 28000 });
      return controller.signal.aborted ? null : data.text;
    } catch (failure) {
      if (!controller.signal.aborted) setError(message(failure));
      return null;
    } finally {
      request.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  return { ask, busy, error, clearError: () => setError('') };
}
