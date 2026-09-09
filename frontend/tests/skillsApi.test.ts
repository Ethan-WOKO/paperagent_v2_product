import { beforeEach, describe, expect, it, vi } from 'vitest';
const http = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }));
vi.mock('../src/api/http', () => ({ default: http }));
import { getSkill, installSkill, listSkills, setSkillEnabled, uninstallSkill } from '../src/api/skills';

describe('user skill upload and management API', () => {
  beforeEach(() => vi.clearAllMocks());
  it('sends both selected files as content and retains explicit empty tools', async () => {
    await installSkill([new File(['# Review'], 'SKILL.md'), new File(['allowed_tools: []'], 'skill.yaml')], ' My copy ');
    expect(http.post).toHaveBeenCalledWith('/skills', {
      name: 'My copy', markdown: { filename: 'SKILL.md', content: '# Review' },
      metadata: { filename: 'skill.yaml', content: 'allowed_tools: []' },
    });
  });
  it('accepts only Markdown with metadata/name omitted', async () => {
    await installSkill([new File(['# Review'], 'SKILL.md')]);
    expect(http.post).toHaveBeenCalledWith('/skills', { markdown: { filename: 'SKILL.md', content: '# Review' } });
  });
  it.each(['run.sh', '../SKILL.md', 'skill.zip'])('rejects unsupported filename %s before HTTP', async name => {
    await expect(installSkill([new File(['# Review'], name)])).rejects.toThrow('仅接受');
    expect(http.post).not.toHaveBeenCalled();
  });
  it('rejects duplicate files and missing Markdown', async () => {
    await expect(installSkill([new File(['a'], 'SKILL.md'), new File(['b'], 'SKILL.md')])).rejects.toThrow('仅接受');
    await expect(installSkill([new File(['name: A'], 'skill.yaml')])).rejects.toThrow('必须选择');
    expect(http.post).not.toHaveBeenCalled();
  });
  it('bounds each file in bytes before reading or posting', async () => {
    await expect(installSkill([new File(['中'.repeat(22_000)], 'SKILL.md')])).rejects.toThrow('64 KiB');
    await expect(installSkill([new File(['a'], 'SKILL.md'), new File(['x'.repeat(16385)], 'skill.yaml')])).rejects.toThrow('16 KiB');
    expect(http.post).not.toHaveBeenCalled();
  });
  it('rejects invalid UTF-8 and blank content', async () => {
    await expect(installSkill([new File([new Uint8Array([0xc3, 0x28])], 'SKILL.md')])).rejects.toThrow('UTF-8');
    await expect(installSkill([new File([' '], 'SKILL.md')])).rejects.toThrow('不能为空');
    expect(http.post).not.toHaveBeenCalled();
  });
  it.each([32001, 33000])('rejects %i ASCII code points before HTTP despite fitting the byte limit', async size => {
    const content = '# Review\n' + 'a'.repeat(size - 9);
    await expect(installSkill([new File([content], 'SKILL.md')])).rejects.toThrow('32000');
    expect(http.post).not.toHaveBeenCalled();
  });
  it('accepts exactly 32000 ASCII code points without truncating the prompt', async () => {
    const content = '# Review\n' + 'a'.repeat(32000 - 9);
    await installSkill([new File([content], 'SKILL.md')]);
    expect(http.post.mock.calls[0][1].markdown.content).toBe(content);
  });
  it('counts supplementary Unicode as one code point while preserving text', async () => {
    const content = '# Review\n' + '😀'.repeat(1000) + 'a'.repeat(31000 - 9);
    expect(content.length).toBe(33000);
    expect(Array.from(content)).toHaveLength(32000);
    await installSkill([new File([content], 'SKILL.md')]);
    expect(http.post.mock.calls[0][1].markdown.content).toBe(content);
    http.post.mockClear();
    await expect(installSkill([new File([content + '😀'], 'SKILL.md')])).rejects.toThrow('32000');
    expect(http.post).not.toHaveBeenCalled();
  });
  it('enforces the 64 KiB byte limit independently of the code-point limit', async () => {
    const content = '# Review\n' + '中'.repeat(21842) + 'a';
    expect(new TextEncoder().encode(content)).toHaveLength(65536);
    await installSkill([new File([content], 'SKILL.md')]);
    expect(http.post.mock.calls[0][1].markdown.content).toBe(content);
    http.post.mockClear();
    await expect(installSkill([new File([content + 'b'], 'SKILL.md')])).rejects.toThrow('64 KiB');
    await expect(installSkill([new File(['# Review\n' + '中'.repeat(32000 - 9)], 'SKILL.md')])).rejects.toThrow('64 KiB');
    expect(http.post).not.toHaveBeenCalled();
  });
  it('counts and preserves BOM, YAML frontmatter and CRLF in the full prompt', async () => {
    const prefix = '\uFEFF---\r\nname: Review\r\n---\r\n';
    const content = prefix + 'a'.repeat(32000 - prefix.length);
    await installSkill([new File([content], 'SKILL.md')]);
    expect(http.post.mock.calls[0][1].markdown.content).toBe(content);
    http.post.mockClear();
    await expect(installSkill([new File([content + 'a'], 'SKILL.md')])).rejects.toThrow('32000');
    expect(http.post).not.toHaveBeenCalled();
  });
  it('maps list/detail/toggle/delete without an owner parameter', () => {
    listSkills(); getSkill('user-123'); setSkillEnabled('user-123', false); uninstallSkill('user-123');
    expect(http.get).toHaveBeenCalledWith('/skills');
    expect(http.get).toHaveBeenCalledWith('/skills/user-123');
    expect(http.put).toHaveBeenCalledWith('/skills/user-123/enabled', { enabled: false });
    expect(http.delete).toHaveBeenCalledWith('/skills/user-123');
  });
});
