import { reactive } from 'vue';
import type { KbDocumentItem } from '../api/knowledge';

type DownloadResponse = { data: Blob; headers: { [key: string]: unknown } };

export function createKnowledgeDownloader(
  request: (id: number) => Promise<DownloadResponse>,
  save: (blob: Blob, filename: string) => void = saveKnowledgeBlob,
) {
  const pending = reactive(new Set<number>());
  async function download(item: KbDocumentItem) {
    if (!item.downloadAvailable || pending.has(item.id)) return;
    pending.add(item.id);
    try {
      const response = await request(item.id);
      const header = String(response.headers['content-disposition'] ?? '');
      const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)?.[1];
      let filename = item.filename;
      if (encoded) {
        try { filename = decodeURIComponent(encoded); } catch { /* Use the listed name. */ }
      }
      filename = filename.replace(/\\/g, '/').split('/').pop()?.replace(/[\u0000-\u001f\u007f]/g, '') || `document-${item.id}`;
      save(response.data, filename);
    } finally {
      pending.delete(item.id);
    }
  }
  return { pending, download };
}

export function saveKnowledgeBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  try {
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
  } finally {
    link.remove();
    URL.revokeObjectURL(url);
  }
}

export function filterKnowledgeDocuments(items: KbDocumentItem[], query: string) {
  const search = query.trim().toLocaleLowerCase();
  return items.filter((item) => item.filename.toLocaleLowerCase().includes(search));
}
