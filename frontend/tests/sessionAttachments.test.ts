import { describe, it, expect, vi, beforeEach } from 'vitest';
const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), delete: vi.fn() }));
vi.mock('../src/api/http', () => ({ default: http }));
import { listSessionAttachments, uploadSessionAttachment, removeSessionAttachment, promoteSessionAttachment, type SessionAttachment } from '../src/api/attachments';
import { chatAttachmentContent } from '../src/utils/paperPolishInput';
import { attachmentSendError, attachmentUploadError } from '../src/utils/sessionAttachments';
const ready: SessionAttachment = { id: 1, filename: 'notes.txt', mimeType: 'text/plain', fileSize: 4, status: 'READY' };
describe('session attachment boundaries', () => {
  beforeEach(() => vi.clearAllMocks());
  it('upload uses the private session endpoint and never merges knowledge chunks', () => {
    const file = new File(['hello'], 'notes.txt', { type: 'text/plain' });
    uploadSessionAttachment(7, file);
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.post.mock.calls[0][0]).toBe('/agent/sessions/7/attachments');
    expect(http.post.mock.calls[0][1].get('file')).toBe(file);
  });
  it('reload and removal remain scoped to the selected session', () => {
    listSessionAttachments(7); removeSessionAttachment(7, 3);
    expect(http.get).toHaveBeenCalledWith('/agent/sessions/7/attachments');
    expect(http.delete).toHaveBeenCalledWith('/agent/sessions/7/attachments/3');
    expect(http.post).not.toHaveBeenCalled();
  });
  it('knowledge ingestion requires its explicit action', () => {
    promoteSessionAttachment(7, 3);
    expect(http.post).toHaveBeenCalledWith('/agent/sessions/7/attachments/3/knowledge');
  });
  it('explicit promotion preserves paper task metadata without routing ordinary questions back to knowledge', () => {
    const content = chatAttachmentContent('总结', [{ documentId: 4, filename: 'paper.tex', status: 'READY' }], 'session');
    expect(content).toContain('paper_polish_start');
    expect(content).toContain('无需知识库检索');
    expect(content).not.toContain('其他资料问题使用 search_knowledge');
  });
  it('typing can continue while sending waits for loading or processing', () => {
    expect(attachmentSendError([ready], true)).not.toBeNull();
    expect(attachmentSendError([{ ...ready, status: 'PROCESSING' }], false)).not.toBeNull();
    expect(attachmentSendError([{ ...ready, status: 'FAILED' }], false)).not.toBeNull();
    expect(attachmentSendError([ready], false)).toBeNull();
    expect(attachmentSendError([], false)).toBeNull();
  });
  it('accepts supported images and rejects oversized or unsupported input', () => {
    expect(attachmentUploadError({ name: 'PHOTO.JPG', size: 100 })).toBeNull();
    expect(attachmentUploadError({ name: 'scan.pdf', size: 100 })).toBeNull();
    expect(attachmentUploadError({ name: 'image.svg', size: 100 })).not.toBeNull();
    expect(attachmentUploadError({ name: 'empty.txt', size: 0 })).not.toBeNull();
    expect(attachmentUploadError({ name: 'huge.png', size: 11 * 1024 * 1024 })).not.toBeNull();
  });
});
