import { computed, ref, watch } from 'vue';
import type { KbDocumentItem } from '../api/knowledge';

export type KnowledgeVisibility = 'all' | 'private' | 'public';

function normalizeVisibility(value: unknown): KnowledgeVisibility {
  return value === 'private' || value === 'public' ? value : 'all';
}

export function useKnowledgeVisibilityFilter(
  userId: () => number | null | undefined,
  storage: () => Pick<Storage, 'getItem' | 'setItem'> = () => window.localStorage,
) {
  const selected = ref<KnowledgeVisibility>('all');
  const key = () => userId() == null ? null : `yanban.knowledge.visibility.${userId()}`;
  watch(userId, () => {
    selected.value = 'all';
    try {
      const userKey = key();
      if (userKey) selected.value = normalizeVisibility(storage().getItem(userKey));
    } catch { /* Storage can be disabled; filtering still works for this visit. */ }
  }, { immediate: true, flush: 'sync' });

  function setVisibility(value: unknown) {
    selected.value = normalizeVisibility(value);
    try {
      const userKey = key();
      if (userKey) storage().setItem(userKey, selected.value);
    } catch { /* A preference write must not block document browsing. */ }
  }

  return { visibility: computed(() => selected.value), setVisibility };
}

export function filterKnowledgeDocuments(items: KbDocumentItem[], query: string, visibility: KnowledgeVisibility = 'all') {
  const search = query.trim().toLocaleLowerCase();
  return items.filter((item) => (visibility === 'all' || item.isPublic === (visibility === 'public'))
    && item.filename.toLocaleLowerCase().includes(search));
}
