import axios from 'axios';
import router from '@/router';
import { expireAuthSession, isJwtExpired } from '@/auth/session';

const http = axios.create({
  baseURL: '/api/v1',
  // One HTTP transport wait; durable Agent work continues and is recovered by status reads.
  timeout: 120000,
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('yanban_access_token');
  if (token) {
    if (isJwtExpired(token)) {
      expireAuthSession();
      void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } });
      return Promise.reject(new axios.Cancel('Access token expired'));
    }
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      expireAuthSession();
      if (router.currentRoute.value.path !== '/login') {
        await router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } });
      }
    }
    return Promise.reject(error);
  },
);

export default http;
