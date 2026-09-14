import http from './http';

export interface SkillListItemResponse {
  id: string;
  name: string;
  source: string;
  path: string;
  enabled: boolean;
  description: string;
  builtin?: boolean;
  managed?: boolean;
}

export interface SkillDetailResponse extends Omit<SkillListItemResponse, 'source' | 'path'> {
  prompt: string;
  metadata: string | null;
  allowedTools: string[];
}

export interface SkillUpload { filename: string; content: string }

export function getSkill(id: string) {
  return http.get<SkillDetailResponse>(`/skills/${encodeURIComponent(id)}`);
}

export function setSkillEnabled(id: string, enabled: boolean) {
  return http.put<void>(`/skills/${encodeURIComponent(id)}/enabled`, { enabled });
}

export function uninstallSkill(id: string) {
  return http.delete<void>(`/skills/${encodeURIComponent(id)}`);
}

// Bounded strict UTF-8 file selection. JSON avoids server multipart temporary files.
export async function installSkill(files: File[], name?: string) {
  if (files.length < 1 || files.length > 2) throw new Error('请选择 SKILL.md 和可选的 skill.yaml，最多两个文件。');
  const uploads: Record<string, SkillUpload> = {};
  for (const file of files) {
    if (!['SKILL.md', 'skill.yaml'].includes(file.name) || uploads[file.name]) {
      throw new Error('仅接受一个 SKILL.md 和可选的一个 skill.yaml，不支持目录或压缩包。');
    }
    const max = file.name === 'SKILL.md' ? 64 * 1024 : 16 * 1024;
    if (file.size > max) throw new Error(`${file.name} 超过 ${max / 1024} KiB 上限。`);
    let content: string;
    try { content = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(await file.arrayBuffer()); }
    catch { throw new Error(`${file.name} 必须是有效 UTF-8 文本。`); }
    if (!content.trim()) throw new Error(`${file.name} 内容不能为空。`);
    // Match runtime JSON Schema maxLength on the exact prompt, including YAML/BOM/CRLF.
    if (file.name === 'SKILL.md' && Array.from(content).length > 32_000) {
      throw new Error('SKILL.md 超过 32000 个 Unicode 代码点上限（含 YAML 头部和换行），并须同时不超过 64 KiB。');
    }
    uploads[file.name] = { filename: file.name, content };
  }
  if (!uploads['SKILL.md']) throw new Error('必须选择 SKILL.md 文件。');
  return http.post<SkillDetailResponse>('/skills', {
    ...(name?.trim() ? { name: name.trim() } : {}),
    markdown: uploads['SKILL.md'],
    ...(uploads['skill.yaml'] ? { metadata: uploads['skill.yaml'] } : {}),
  });
}

export function listSkills() {
  return http.get<SkillListItemResponse[]>('/skills');
}
