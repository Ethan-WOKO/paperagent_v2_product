import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import router from './router';
import './styles.css';
import './design-system.css';
import './styles/paper-workspace.css';
import './styles/knowledge-workspace.css';
import { useAuthStore } from './stores/auth';
import { AUTH_EXPIRED_EVENT } from './auth/session';

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);

const uiMockRequested = import.meta.env.DEV && (
  import.meta.env.VITE_UI_MOCK === 'true'
  || new URLSearchParams(window.location.search).has('uiMock')
);

async function bootstrap() {
  if (uiMockRequested) {
    const { installUiMock } = await import('./mocks/runtime');
    installUiMock();
  }

  const authStore = useAuthStore();
  authStore.restore();
  window.addEventListener(AUTH_EXPIRED_EVENT, () => {
    authStore.clear();
    if (router.currentRoute.value.name !== 'login') {
      void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } });
    }
  });

  await authStore.fetchCurrentUser();
  app.use(router);
  await router.isReady();
  if (!authStore.isAuthenticated && router.currentRoute.value.name !== 'login') {
    await router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } });
  }
  app.mount('#app');
}

void bootstrap();
