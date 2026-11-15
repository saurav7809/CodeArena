import { useEffect, useRef, useState } from 'react';
import { api, message } from './api';
import { isPending } from './components/SubmissionResults';
import { storage } from './storage';

function restore(key) {
  if (!key) return null;
  try {
    const saved = JSON.parse(storage.session.get(key));
    return Number.isSafeInteger(saved?.id) && saved.id > 0 ? saved : null;
  } catch {
    return null;
  }
}

function retryable(error) {
  const status = error.response?.status;
  return !status || status >= 500 || status === 429;
}

export function useExecution(storageKey) {
  const [job, setJob] = useState(() => restore(storageKey));
  const [result, setResult] = useState(null);
  const [sending, setSending] = useState(false);
  const [failedRequest, setFailedRequest] = useState(null);
  const [error, setError] = useState('');
  const [resume, setResume] = useState(0);
  const [pollFailed, setPollFailed] = useState(false);
  const inFlight = useRef(null);
  const pending = job && (!result || isPending(result.submission));
  const pollId = pending ? job.id : null;

  useEffect(() => () => { inFlight.current?.abort(); }, []);
  useEffect(() => {
    if (!pollId) return;
    const controller = new AbortController();
    let timer;
    let attempts = 0;
    let delay = 600;
    function schedule(wait) {
      timer = setTimeout(poll, document.hidden ? Math.max(5000, wait) : wait);
    }
    async function poll() {
      try {
        const { data } = await api.get('/submissions/' + pollId + '/result', { signal: controller.signal });
        if (controller.signal.aborted) return;
        attempts = 0;
        setError('');
        setResult(data);
        if (isPending(data.submission)) {
          schedule(delay);
          delay = Math.min(3000, delay + 250);
        }
      } catch (failure) {
        if (controller.signal.aborted) return;
        if (retryable(failure) && attempts < 3) {
          const cooldown = Number(failure.response?.headers?.['retry-after']) * 1000;
          const backoff = 1000 * 2 ** attempts++;
          schedule(cooldown > 0 ? Math.min(60000, Math.max(backoff, cooldown)) : backoff);
          return;
        }
        setError(message(failure));
        const unavailable = [401, 403, 404].includes(failure.response?.status);
        if (unavailable) {
          storage.session.set(storageKey, null);
          setJob(null);
        } else {
          setPollFailed(true);
        }
      }
    }
    schedule(0);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [pollId, resume, storageKey]);

  async function send(request) {
    if (inFlight.current || pending) return false;
    const controller = new AbortController();
    inFlight.current = controller;
    setSending(true);
    setError('');
    setFailedRequest(null);
    try {
      const { data } = await api.post('/submissions/' + request.action, request.payload, {
        signal: controller.signal,
        headers: { 'Idempotency-Key': request.key },
      });
      if (controller.signal.aborted) return false;
      const next = { id: data.id, custom: request.payload.customInput !== null };
      storage.session.set(storageKey, JSON.stringify(next));
      setResult(isPending(data) ? { submission: data, diagnostic: null, cases: [] } : null);
      setJob(next);
      return true;
    } catch (failure) {
      if (!controller.signal.aborted) {
        setError(message(failure));
        if (retryable(failure)) setFailedRequest(request);
      }
      return false;
    } finally {
      inFlight.current = null;
      if (!controller.signal.aborted) setSending(false);
    }
  }

  return {
    result,
    custom: job?.custom,
    hasJob: Boolean(job),
    restoring: Boolean(job && !result && !error),
    busy: sending || Boolean(pending),
    error,
    needsRetry: Boolean(failedRequest) || pollFailed,
    async retry() {
      if (failedRequest) return send(failedRequest);
      setError('');
      setPollFailed(false);
      setResume((previous) => previous + 1);
      return false;
    },
    queue: (action, payload) => send({ action, payload, key: crypto.randomUUID() }),
  };
}
