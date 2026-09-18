import type { SessionAttachment } from '@/api/attachments';
export function attachmentSendError(items: SessionAttachment[], loading: boolean): string | null {
  if (loading) return '附件正在上传或加载，请稍后发送';
  if (items.some(item => item.status !== 'READY')) return '请先移除失败附件，或等待附件就绪';
  return null;
}
export function attachmentUploadError(file: Pick<File, 'size' | 'name'>): string | null {
  if (file.size === 0 || file.size > 10 * 1024 * 1024) return '附件不能为空且不能超过 10 MB';
  if (!/\.(pdf|docx?|txt|md|tex|bib|csv|json|png|jpe?g)$/i.test(file.name)) return '暂不支持该附件格式';
  return null;
}
