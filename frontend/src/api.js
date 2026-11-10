import axios from 'axios';

export const api = axios.create({ baseURL: '/api', timeout: 12000 });
let token = sessionStorage.getItem('arena-token');

export function setToken(value) {
  token = value;
  if (value) sessionStorage.setItem('arena-token', value);
  else sessionStorage.removeItem('arena-token');
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
