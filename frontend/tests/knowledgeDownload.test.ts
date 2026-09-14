import { afterEach, describe, expect, it, vi } from 'vitest';
import { createKnowledgeDownloader, filterKnowledgeDocuments, saveKnowledgeBlob } from '../src/knowledge/documentDownload';
import type { KbDocumentItem } from '../src/api/knowledge';

const item = (overrides: Partial<KbDocumentItem> = {}) => ({
  id: 1, filename: '资料.pdf', ownedByCurrentUser: true, administratorPublic: false,
  downloadAvailable: true, ...overrides,
}) as KbDocumentItem;

afterEach(() => vi.unstubAllGlobals());

describe('knowledge file downloads', () => {
  it('downloads the original blob and uses the server filename', async () => {
    const blob = new Blob(['original bytes']);
    const request = vi.fn().mockResolvedValue({ data: blob, headers: {
      'content-disposition': "attachment; filename*=UTF-8''%E8%AF%B4%E6%98%8E%20%E6%96%87%E6%A1%A3.pdf",
    }});
    const save = vi.fn();
    const downloader = createKnowledgeDownloader(request, save);
    await downloader.download(item());
    expect(request).toHaveBeenCalledWith(1);
    expect(save).toHaveBeenCalledWith(blob, '说明 文档.pdf');
    expect(downloader.pending.size).toBe(0);
  });

  it('suppresses duplicate clicks until the original request completes', async () => {
    let finish!: (value: unknown) => void;
    const request = vi.fn(() => new Promise<any>((resolve) => { finish = resolve; }));
    const downloader = createKnowledgeDownloader(request, vi.fn());
    const first = downloader.download(item());
    expect(downloader.pending.has(1)).toBe(true);
    await downloader.download(item());
    expect(request).toHaveBeenCalledTimes(1);
    finish({ data: new Blob(['x']), headers: {} });
    await first;
    expect(downloader.pending.size).toBe(0);
  });

  it('never saves an error blob and releases loading for retry', async () => {
    const failure = { response: { status: 404, data: new Blob(['error']) } };
    const request = vi.fn().mockRejectedValue(failure);
    const save = vi.fn();
    const downloader = createKnowledgeDownloader(request, save);
    await expect(downloader.download(item())).rejects.toBe(failure);
    expect(save).not.toHaveBeenCalled();
    expect(downloader.pending.size).toBe(0);
  });

  it('does not request originals declared unavailable', async () => {
    const request = vi.fn();
    await createKnowledgeDownloader(request, vi.fn()).download(item({ downloadAvailable: false }));
    expect(request).not.toHaveBeenCalled();
  });

  it('downloads administrator public items without requiring ownership', async () => {
    const request = vi.fn().mockResolvedValue({ data: new Blob(['x']), headers: {} });
    const save = vi.fn();
    await createKnowledgeDownloader(request, save).download(item({ ownedByCurrentUser: false, administratorPublic: true }));
    expect(save).toHaveBeenCalledOnce();
  });

  it('filters filenames locally without modifying the source list', () => {
    const items = [item(), item({ id: 2, filename: 'Guide.PDF' })];
    expect(filterKnowledgeDocuments(items, ' guide ')).toEqual([items[1]]);
    expect(filterKnowledgeDocuments(items, '资料')).toEqual([items[0]]);
    expect(filterKnowledgeDocuments(items, 'missing')).toEqual([]);
    expect(filterKnowledgeDocuments(items, '')).toEqual(items);
    expect(items).toHaveLength(2);
  });

  it('removes the link and revokes the temporary URL even if click fails', () => {
    const remove = vi.fn();
    const link = { href: '', download: '', click: vi.fn(() => { throw new Error('click failed'); }), remove };
    const revoke = vi.fn();
    vi.stubGlobal('URL', { createObjectURL: () => 'blob:temporary', revokeObjectURL: revoke });
    vi.stubGlobal('document', { createElement: () => link, body: { appendChild: vi.fn() } });
    expect(() => saveKnowledgeBlob(new Blob(['x']), '资料.pdf')).toThrow('click failed');
    expect(link.download).toBe('资料.pdf');
    expect(remove).toHaveBeenCalledOnce();
    expect(revoke).toHaveBeenCalledWith('blob:temporary');
  });
});
