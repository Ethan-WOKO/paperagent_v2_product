<template>
  <NCard id="skills-settings" class="workbench-card scholar-card settings-section-card" :bordered="false">
    <template #header>{{ copy('技能', 'Skills') }}</template>
    <NSpace vertical>
      <p class="skill-hint">{{ copy('安装自己的技能，在聊天和项目的技能选择器中使用。更改即时保存。', 'Install your skills for the chat and project skill selectors. Changes save immediately.') }}</p>
      <label class="skill-field">
        <span>{{ copy('安装名称（可选，优先于文件中的名称）', 'Installation name (optional, overrides the file name metadata)') }}</span>
        <NInput v-model:value="name" :maxlength="100" :disabled="disabled || busy" :placeholder="copy('留空使用 YAML name 或 Markdown 一级标题', 'Use YAML name or Markdown heading when blank')" />
      </label>
      <label class="skill-field">
        <span>{{ copy('选择 SKILL.md，可同时选择 skill.yaml', 'Select SKILL.md, optionally together with skill.yaml') }}</span>
        <input ref="picker" data-testid="skill-files" type="file" accept=".md,.yaml" multiple :disabled="disabled || busy" @change="selectFiles" />
      </label>
      <p class="skill-hint">{{ copy('SKILL.md 须同时 ≤ 64 KiB、≤ 32000 个 Unicode 代码点（含 YAML 头部和换行）；skill.yaml ≤ 16 KiB，每人最多 100 项；仅支持 UTF-8 文本。', 'SKILL.md must satisfy both ≤ 64 KiB and ≤ 32000 Unicode code points (including YAML frontmatter and line endings); skill.yaml ≤ 16 KiB, up to 100 per user; UTF-8 text only.') }}</p>
      <p class="skill-hint">{{ copy('缺省 allowed_tools 时仅声明项目列表、项目读取和知识检索工具；显式 [] 禁用全部工具。实际可用工具仍受当前会话权限限制。', 'Omitting allowed_tools defaults to project listing, project reading and knowledge search. Explicit [] disables all tools. Session permissions still apply.') }}</p>
      <details class="skill-format">
        <summary>{{ copy('文件格式与工具默认值', 'File format and tool defaults') }}</summary>
        <p>{{ copy('YAML 支持 name、description、allowed_tools（或 allowed-tools）。两个文件同名字段必须一致。', 'YAML supports name, description, allowed_tools (or allowed-tools). Matching fields in both files must agree.') }}</p>
        <p>{{ copy('工具名须为 1–64 个 ASCII 字符，以小写字母开头，后续仅允许小写字母、数字、下划线；不接受大写、点或连字符。', 'Tool names must be 1–64 ASCII characters: a lowercase letter followed by lowercase letters, digits or underscores. Uppercase letters, dots and hyphens are not accepted.') }}</p>
        <pre>---
name: my-review
description: Review supplied material
allowed_tools:
  - list_project_files
  - read_project_file
  - search_knowledge
---
Review the material and explain your findings.</pre>
      </details>
      <NSpace>
        <NButton data-testid="skill-install" type="primary" :loading="busy" :disabled="disabled || busy || files.length === 0" @click="install">{{ copy('安装技能', 'Install skill') }}</NButton>
        <NButton data-testid="skill-refresh" :disabled="busy || loading" @click="reload">{{ copy('刷新列表', 'Refresh list') }}</NButton>
      </NSpace>
      <NAlert v-if="error" type="error" role="alert">{{ error }}</NAlert>
      <NEmpty v-if="!loading && !error && skills.length === 0" :description="copy('暂无可用技能。', 'No skills found.')" />
      <p v-if="loading" role="status">{{ copy('正在加载技能…', 'Loading skills…') }}</p>
      <article v-for="skill in skills" :key="skill.id" class="skill-row" :data-skill-id="skill.id">
        <div class="skill-heading">
          <strong>{{ skill.name }}</strong>
          <span>{{ skill.builtin || skill.source === 'builtin' ? copy('内置', 'Built-in') : skill.managed ? copy('个人', 'Personal') : copy('服务器预置', 'Server-provided') }}</span>
        </div>
        <p v-if="skill.description">{{ skill.description }}</p>
        <NSpace align="center">
          <NSwitch :value="skill.enabled" :disabled="disabled || busy" :aria-label="`${skill.name} ${copy('启用', 'enabled')}`" @update:value="(value) => toggle(skill, value)" />
          <span>{{ skill.enabled ? copy('已启用', 'Enabled') : copy('已禁用', 'Disabled') }}</span>
          <NButton size="small" :disabled="busy" @click="showDetail(skill.id)">{{ copy('详情', 'Details') }}</NButton>
          <NButton v-if="skill.managed" size="small" type="error" :disabled="disabled || busy" @click="pendingDelete = skill">{{ copy('卸载', 'Uninstall') }}</NButton>
        </NSpace>
      </article>
      <NAlert v-if="pendingDelete" type="warning">
        {{ copy('卸载后将从个人列表移除，需要重新上传才能使用：', 'Uninstalling removes this skill; upload it again to use it: ') }}{{ pendingDelete.name }}
        <NSpace>
          <NButton data-testid="skill-confirm-delete" :disabled="disabled || busy" type="error" @click="remove">{{ copy('确认卸载', 'Confirm uninstall') }}</NButton>
          <NButton :disabled="busy" @click="pendingDelete = null">{{ copy('取消', 'Cancel') }}</NButton>
        </NSpace>
      </NAlert>
      <section v-if="detail" class="skill-detail" aria-live="polite">
        <div class="skill-heading"><strong>{{ detail.name }}</strong><NButton size="small" @click="detail = null">{{ copy('关闭详情', 'Close details') }}</NButton></div>
        <p>{{ copy('允许工具（仍受会话权限约束）：', 'Allowed tools (subject to session permissions): ') }}{{ detail.allowedTools.length ? detail.allowedTools.join(', ') : copy('无，禁止所有工具', 'None; all tools denied') }}</p>
        <pre tabindex="0">{{ detail.prompt }}</pre>
        <details v-if="detail.metadata"><summary>skill.yaml</summary><pre tabindex="0">{{ detail.metadata }}</pre></details>
      </section>
    </NSpace>
  </NCard>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { NAlert, NButton, NCard, NEmpty, NInput, NSpace, NSwitch } from 'naive-ui';
import { getSkill, installSkill, listSkills, setSkillEnabled, uninstallSkill, type SkillDetailResponse, type SkillListItemResponse } from '@/api/skills';
import { apiErrorMessage } from '@/api/errors';
import { useI18n } from '@/composables/useI18n';
import { ui } from '@/ui';

const props = defineProps<{ disabled?: boolean }>();
const { isEnglish } = useI18n();
const copy = (zh: string, en: string) => isEnglish.value ? en : zh;
const name = ref('');
const files = ref<File[]>([]);
const picker = ref<HTMLInputElement | null>(null);
const skills = ref<SkillListItemResponse[]>([]);
const detail = ref<SkillDetailResponse | null>(null);
const pendingDelete = ref<SkillListItemResponse | null>(null);
const busy = ref(false);
const loading = ref(false);
const error = ref('');
let listRequest = 0;

function selectFiles(event: Event) {
  files.value = Array.from((event.target as HTMLInputElement).files || []);
  error.value = '';
}

async function reload() {
  const request = ++listRequest;
  loading.value = true;
  try {
    const { data } = await listSkills();
    if (request === listRequest) { skills.value = data; error.value = ''; }
  } catch (reason) {
    if (request === listRequest) error.value = apiErrorMessage(reason, copy('加载技能失败，请重试。', 'Failed to load skills. Please retry.'));
  } finally { if (request === listRequest) loading.value = false; }
}

async function mutate(action: () => Promise<void>) {
  if (props.disabled || busy.value) return;
  busy.value = true;
  error.value = '';
  try { await action(); await reload(); }
  catch (reason) { error.value = apiErrorMessage(reason, copy('技能操作失败，请重试。', 'Skill operation failed. Please retry.')); }
  finally { busy.value = false; }
}

async function install() {
  await mutate(async () => {
    const { data } = await installSkill(files.value, name.value);
    detail.value = data;
    name.value = '';
    files.value = [];
    if (picker.value) picker.value.value = '';
    ui.message.success(copy('技能已安装，可在聊天和项目中选择使用。', 'Skill installed. Select it in chat or a project.'));
  });
}

async function toggle(skill: SkillListItemResponse, enabled: boolean) {
  await mutate(async () => {
    await setSkillEnabled(skill.id, enabled);
    skill.enabled = enabled;
    if (detail.value?.id === skill.id) detail.value.enabled = enabled;
  });
}

async function showDetail(id: string) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  detail.value = null;
  try { detail.value = (await getSkill(id)).data; }
  catch (reason) { error.value = apiErrorMessage(reason, copy('加载技能详情失败。', 'Failed to load skill details.')); }
  finally { busy.value = false; }
}

async function remove() {
  const skill = pendingDelete.value;
  if (!skill) return;
  await mutate(async () => {
    await uninstallSkill(skill.id);
    skills.value = skills.value.filter(item => item.id !== skill.id);
    if (detail.value?.id === skill.id) detail.value = null;
    pendingDelete.value = null;
    ui.message.success(copy('技能已卸载。可重新上传恢复使用。', 'Skill uninstalled. Upload it again to restore it.'));
  });
}

onMounted(reload);
</script>

<style scoped>
.skill-hint, .skill-heading span { color: var(--yb-text-secondary); font-size: 13px; }
.skill-field { display: grid; gap: 6px; }
.skill-field input { max-width: 100%; }
.skill-row { padding: 12px 0; border-top: 1px solid var(--yb-border); overflow-wrap: anywhere; }
.skill-heading { display: flex; gap: 12px; justify-content: space-between; align-items: center; }
.skill-row p, .skill-hint { margin: 4px 0; }
.skill-detail, .skill-format { min-width: 0; overflow-wrap: anywhere; }
pre { max-height: 320px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; padding: 10px; background: var(--yb-bg); }
</style>
