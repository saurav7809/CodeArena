import { useCallback, useEffect, useState } from 'react';
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
