import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';
import LoginPage from '@/views/LoginPage.vue';
import RegisterPage from '@/views/RegisterPage.vue';
import DemoPage from '@/views/DemoPage.vue';
import SettingsPage from '@/views/SettingsPage.vue';
import MemorySettingsPage from '@/views/MemorySettingsPage.vue';
import KnowledgeBasePage from '@/views/KnowledgeBasePage.vue';
import KnowledgeSearchDebugPage from '@/views/KnowledgeSearchDebugPage.vue';
import PaperPage from '@/views/PaperPage.vue';
import ProjectPreviewPage from '@/views/ProjectPreviewPage.vue';
import AdminPage from '@/views/AdminPage.vue';
import { isJwtExpired } from '@/auth/session';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/projects' },
    { path: '/demo', name: 'demo', component: DemoPage },
    { path: '/login', name: 'login', component: LoginPage, meta: { guestOnly: true } },
    { path: '/register', name: 'register', component: RegisterPage, meta: { guestOnly: true } },
    { path: '/paper', name: 'paper', component: PaperPage, meta: { requiresAuth: true } },
    { path: '/projects', name: 'projects', component: ProjectPreviewPage, meta: { requiresAuth: true } },
    { path: '/knowledge-base', name: 'knowledge-base', component: KnowledgeBasePage, meta: { requiresAuth: true } },
    { path: '/knowledge-base/search-debug', name: 'knowledge-base-search-debug', component: KnowledgeSearchDebugPage, meta: { requiresAuth: true } },
    { path: '/settings', name: 'settings', component: SettingsPage, meta: { requiresAuth: true } },
    { path: '/settings/memory', name: 'memory-settings', component: MemorySettingsPage, meta: { requiresAuth: true } },
    { path: '/admin', name: 'admin', component: AdminPage, meta: { requiresAuth: true, requiresAdmin: true } },
    { path: '/:pathMatch(.*)*', redirect: '/projects' },
  ],
});

router.beforeEach(async (to) => {
  const authStore = useAuthStore();
  if (authStore.token && isJwtExpired(authStore.token)) {
    authStore.clear();
  }
  const hasToken = Boolean(authStore.token);

  if (!hasToken && to.meta.requiresAuth) {
    return { path: '/login', query: to.fullPath === '/' ? undefined : { redirect: to.fullPath } };
  }

  if (authStore.token && !authStore.ready) {
    await authStore.fetchCurrentUser();
  }

  if (hasToken && to.meta.requiresAuth && !authStore.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  if (to.meta.requiresAdmin && authStore.currentUser?.role !== 'ADMIN') {
    return '/projects';
  }
  if (to.meta.guestOnly && authStore.isAuthenticated) {
    return '/projects';
  }
  return true;
});

export default router;
