import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createRenderer, nextTick, ref, ssrContextKey } from 'vue';
import { readFileSync } from 'node:fs';
const api = vi.hoisted(() => ({ listSkills: vi.fn(), installSkill: vi.fn(), getSkill: vi.fn(), setSkillEnabled: vi.fn(), uninstallSkill: vi.fn() }));
vi.mock('../src/api/skills', () => api);
vi.mock('../src/ui', () => ({ ui: { message: { success: vi.fn() } } }));
vi.mock('../src/composables/useI18n', () => ({ useI18n: () => ({ isEnglish: ref(false) }) }));
vi.mock('naive-ui', () => ({ NCard: {}, NAlert: {}, NEmpty: {}, NSpace: {}, NButton: {}, NInput: {}, NSwitch: {} }));
import SkillManagement from '../src/components/SkillManagement.vue';

// Test actual component setup/lifecycle under Node, which compiles SFCs for SSR.
const renderer = createRenderer({
  createElement: () => ({}), createText: () => ({}), createComment: () => ({}),
  setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null,
  patchProp() {}, insert() {}, remove() {},
});
const personal = { id: 'user-1', name: 'Private review', enabled: true, managed: true, builtin: false, source: 'user', path: '', description: 'Private' };
const builtin = { ...personal, id: 'code-review', name: 'Code review', managed: false, builtin: true, source: 'builtin' };
async function mount(disabled = false) {
  let state: any;
  const component = SkillManagement as any;
  const app = renderer.createApp({ ...component, render: () => null, setup(props: any, context: any) {
    state = component.setup(props, context); return state;
  } }, { disabled });
  app.provide(ssrContextKey, {});
  app.mount({});
  for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); }
  return { state, app };
}

describe('SkillManagement behavior', () => {
  beforeEach(() => { vi.resetAllMocks(); api.listSkills.mockResolvedValue({ data: [builtin] }); });
  it('installs both files, reloads persisted list and exposes effective tools', async () => {
    const { state, app } = await mount();
    const files = [new File(['# Review'], 'SKILL.md'), new File(['allowed_tools: []'], 'skill.yaml')];
    state.selectFiles({ target: { files } });
    api.installSkill.mockResolvedValue({ data: { ...personal, prompt: '# Review', metadata: 'allowed_tools: []', allowedTools: [] } });
    api.listSkills.mockResolvedValue({ data: [builtin, personal] });
    await state.install();
    expect(api.installSkill).toHaveBeenCalledWith(files, '');
    expect(state.skills.value).toContainEqual(personal);
    expect(state.detail.value.allowedTools).toEqual([]);
    expect(state.files.value).toEqual([]);
    app.unmount();
    const refreshed = await mount();
    expect(refreshed.state.skills.value).toContainEqual(personal);
    refreshed.app.unmount();
  });
  it('persists enablement immediately', async () => {
    api.listSkills.mockResolvedValue({ data: [builtin, { ...personal }] });
    const { state, app } = await mount();
    await state.toggle(state.skills.value[1], false);
    expect(api.setSkillEnabled).toHaveBeenCalledWith('user-1', false);
    expect(state.skills.value[1].enabled).toBe(false);
    app.unmount();
  });
  it('requires an uninstall target and refreshes after deletion', async () => {
    api.listSkills.mockResolvedValue({ data: [{ ...personal }] });
    const { state, app } = await mount();
    await state.remove();
    expect(api.uninstallSkill).not.toHaveBeenCalled();
    state.pendingDelete.value = personal;
    api.listSkills.mockResolvedValue({ data: [] });
    await state.remove();
    expect(api.uninstallSkill).toHaveBeenCalledWith('user-1');
    expect(state.skills.value).toEqual([]);
    expect(state.pendingDelete.value).toBeNull();
    app.unmount();
  });
  it('shows server failures and preserves previous enablement', async () => {
    const { state, app } = await mount();
    api.setSkillEnabled.mockRejectedValue({ response: { data: { message: 'Skill 已被禁用或删除，请刷新' } } });
    const skill = { ...personal };
    await state.toggle(skill, false);
    expect(state.error.value).toContain('Skill 已被禁用或删除，请刷新');
    expect(skill.enabled).toBe(true);
    api.getSkill.mockRejectedValue({ response: { data: { message: 'Skill 不存在或无权访问' } } });
    await state.showDetail(skill.id);
    expect(state.error.value).toContain('Skill 不存在或无权访问');
    expect(state.detail.value).toBeNull();
    app.unmount();
  });
  it('preserves the demo-user mutation guard', async () => {
    const { state, app } = await mount(true);
    await state.install(); await state.toggle(personal, false);
    state.pendingDelete.value = personal; await state.remove();
    expect(api.installSkill).not.toHaveBeenCalled();
    expect(api.setSkillEnabled).not.toHaveBeenCalled();
    expect(api.uninstallSkill).not.toHaveBeenCalled();
    app.unmount();
  });
  it.each(['Read', 'read.file', 'read-file'])('shows server tool-name rejection for %s without adding a skill', async tool => {
    const { state, app } = await mount();
    const files = [new File(['# Review'], 'SKILL.md'), new File([`allowed_tools: [${tool}]`], 'skill.yaml')];
    state.selectFiles({ target: { files } });
    const message = '工具名称必须唯一，长度为 1–64 个 ASCII 字符，以小写字母开头，后续仅允许小写字母、数字、下划线；不支持大写、点或连字符';
    api.installSkill.mockRejectedValue({ response: { status: 400, data: { message } } });
    await state.install();
    expect(state.error.value).toBe(message);
    expect(state.skills.value).toEqual([builtin]);
    expect(state.detail.value).toBeNull();
    expect(state.files.value).toEqual(files);
    expect(state.busy.value).toBe(false);
    app.unmount();
  });
  it('integrates Settings without stale saves and keeps both selectors wired', () => {
    const settings = readFileSync(new URL('../src/views/SettingsPage.vue', import.meta.url), 'utf8');
    expect(settings).toContain('<SkillManagement :disabled="isDemoUser" />');
    expect(settings).not.toContain('disabledSkills:');
    const component = readFileSync(new URL('../src/components/SkillManagement.vue', import.meta.url), 'utf8');
    expect(component).toContain('v-if="skill.managed"');
    expect(component).toContain('multiple');
    expect(component).toContain('32000 个 Unicode 代码点');
    expect(component).toContain('≤ 64 KiB');
    expect(component).toContain('1–64 个 ASCII 字符');
    expect(component).toContain('{{ detail.prompt }}');
    expect(component).not.toContain('v-html');
    for (const file of ['ChatPage.vue', 'ProjectPreviewPage.vue']) {
      const page = readFileSync(new URL('../src/views/' + file, import.meta.url), 'utf8');
      expect(page).toContain('listSkills()');
      expect(page).toMatch(/\.filter\(\(?skill\)? => skill\.enabled\)/);
      expect(page).toContain('skillId');
    }
  });
});
