import axios from 'axios';
import { storage } from './storage';

export const apiOrigin = (import.meta.env.VITE_API_URL || '').trim().replace(/\/$/, '');
export const api = axios.create({ baseURL: apiOrigin + '/api', timeout: 12000 });
let token = storage.session.get('arena-token');

export function setToken(value) {
  token = value;
  storage.session.set('arena-token', value);
}

export function hasToken() {
  return Boolean(token);
}

api.interceptors.request.use((request) => {
  if (token && !['/auth/login', '/auth/register'].includes(request.url)) {
    request.headers.Authorization = 'Bearer ' + token;
  }
  return request;
});

api.interceptors.response.use(undefined, (error) => {
  if (error.response?.status === 401 && error.config?.headers?.Authorization === 'Bearer ' + token) {
    setToken(null);
    window.dispatchEvent(new Event('arena:session-expired'));
  }
  return Promise.reject(error);
});

export function message(error) {
  return error.response?.data?.message || error.message || 'Something went wrong. Try again.';
}

export function url(path, params) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== '' && value != null) query.set(key, String(value));
  }
  return query.size ? path + '?' + query.toString() : path;
}
