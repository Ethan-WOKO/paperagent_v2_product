import { effectScope, ref } from 'vue';
import { describe, expect, it, vi } from 'vitest';
import { filterKnowledgeDocuments, useKnowledgeVisibilityFilter } from '../src/knowledge/documentFilters';
import type { KbDocumentItem } from '../src/api/knowledge';

const documents = [
  { id: 1, filename: 'Notes.md', isPublic: false, ownedByCurrentUser: true },
  { id: 2, filename: 'Notes-public.md', isPublic: true, ownedByCurrentUser: true },
  { id: 3, filename: 'Admin Guide.pdf', isPublic: true, ownedByCurrentUser: false, administratorPublic: true },
] as KbDocumentItem[];

describe('knowledge visibility filters', () => {
  it('defaults to all and classifies by visibility, not ownership', () => {
    expect(filterKnowledgeDocuments(documents, '')).toEqual(documents);
    expect(filterKnowledgeDocuments(documents, '', 'private')).toEqual([documents[0]]);
    expect(filterKnowledgeDocuments(documents, '', 'public')).toEqual(documents.slice(1));
    expect(documents).toHaveLength(3);
  });

  it('intersects filename and visibility and supports empty results', () => {
    expect(filterKnowledgeDocuments(documents, ' NOTES ', 'public')).toEqual([documents[1]]);
    expect(filterKnowledgeDocuments(documents, 'Admin', 'private')).toEqual([]);
    expect(filterKnowledgeDocuments([], '', 'all')).toEqual([]);
  });

  it('restores preferences across visits and isolates account changes', () => {
    const values = new Map<string, string>();
    const storage = { getItem: (key: string) => values.get(key) ?? null, setItem: vi.fn((key: string, value: string) => { values.set(key, value); }) };
    const user = ref<number | undefined>(1);
    const scope = effectScope();
    const filter = scope.run(() => useKnowledgeVisibilityFilter(() => user.value, () => storage))!;
    expect(filter.visibility.value).toBe('all');
    filter.setVisibility('private');
    user.value = 2;
    expect(filter.visibility.value).toBe('all');
    filter.setVisibility('public');
    user.value = 1;
    expect(filter.visibility.value).toBe('private');
    expect(storage.setItem).toHaveBeenCalledTimes(2);
    scope.stop();
    const revisit = effectScope();
    expect(revisit.run(() => useKnowledgeVisibilityFilter(() => 2, () => storage))!.visibility.value).toBe('public');
    revisit.stop();
  });

  it('does not persist without an identity and resets on logout', () => {
    const storage = { getItem: vi.fn(() => 'private'), setItem: vi.fn() };
    const user = ref<number | undefined>();
    const scope = effectScope();
    const filter = scope.run(() => useKnowledgeVisibilityFilter(() => user.value, () => storage))!;
    filter.setVisibility('public');
    expect(storage.setItem).not.toHaveBeenCalled();
    user.value = 1;
    expect(filter.visibility.value).toBe('private');
    user.value = undefined;
    expect(filter.visibility.value).toBe('all');
    scope.stop();
  });

  it('falls back for invalid values and persists clearing to all', () => {
    const storage = { getItem: () => 'obsolete', setItem: vi.fn() };
    const scope = effectScope();
    const filter = scope.run(() => useKnowledgeVisibilityFilter(() => 1, () => storage))!;
    expect(filter.visibility.value).toBe('all');
    filter.setVisibility('private');
    filter.setVisibility('all');
    expect(filter.visibility.value).toBe('all');
    expect(storage.setItem).toHaveBeenLastCalledWith('yanban.knowledge.visibility.1', 'all');
    scope.stop();
  });

  it('keeps filtering usable when browser storage is blocked', () => {
    const scope = effectScope();
    const filter = scope.run(() => useKnowledgeVisibilityFilter(() => 1, () => { throw new Error('blocked'); }))!;
    expect(filter.visibility.value).toBe('all');
    expect(() => filter.setVisibility('private')).not.toThrow();
    expect(filter.visibility.value).toBe('private');
    scope.stop();
  });
});
