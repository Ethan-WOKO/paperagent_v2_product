<template>
  <div v-if="variant === 'workspace'" class="workspace-markdown">
    <div class="message-markdown message-markdown--workspace" @click="openCitation" v-html="rendered.html" />
    <details v-if="rendered.citations.length" ref="citationDetails" class="markdown-sources">
      <summary>{{ t('chat.markdown.sources', { count: rendered.citations.length }) }}</summary>
      <ol>
        <li v-for="(source, index) in rendered.citations" :key="source" :data-source-index="index + 1" tabindex="-1">
          {{ source }}
        </li>
      </ol>
    </details>
  </div>
  <div v-else class="message-markdown" v-html="rendered.html" />
</template>

<script setup lang="ts">
import DOMPurify from 'dompurify';
import MarkdownIt from 'markdown-it';
import { computed, ref } from 'vue';
import { useI18n } from '@/composables/useI18n';
import { configureMarkdownCitations, type MarkdownCitationEnvironment } from '@/utils/markdownCitations';
import { configureMarkdownLinkPolicy } from '@/utils/markdownLinkPolicy';
import { normalizeLooseMarkdown } from '@/utils/markdownNormalization';

const props = defineProps<{
  content: string;
  variant?: 'default' | 'project' | 'workspace';
}>();

const { t } = useI18n();
const citationDetails = ref<HTMLDetailsElement>();
const markdown = configureMarkdownCitations(configureMarkdownLinkPolicy(new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})));

const defaultLinkOpen = markdown.renderer.rules.link_open
  || ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options));

markdown.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx];
  if (token.attrIndex('href') >= 0) {
    token.attrSet('target', '_blank');
    token.attrSet('rel', 'noopener noreferrer');
  }
  return defaultLinkOpen(tokens, idx, options, env, self);
};

const rendered = computed(() => {
  const env: MarkdownCitationEnvironment = {
    workspaceCitations: props.variant === 'workspace',
    citations: [],
    citationLabel: (index) => t('chat.markdown.viewSource', { index }),
  };
  const html = DOMPurify.sanitize(markdown.render(normalizeLooseMarkdown(props.content || '', {
    demoteSpacedProseHeadings: props.variant === 'project' || props.variant === 'workspace',
  }), env));
  return { html, citations: env.citations };
});

function openCitation(event: MouseEvent) {
  const target = event.target;
  if (!(target instanceof Element)) return;
  const button = target.closest<HTMLButtonElement>('button[data-citation-index]');
  const index = Number(button?.dataset.citationIndex);
  const details = citationDetails.value;
  if (!details || !Number.isInteger(index) || index < 1 || index > rendered.value.citations.length) return;
  details.open = true;
  const source = details.querySelector<HTMLElement>(`[data-source-index="${index}"]`);
  source?.focus({ preventScroll: true });
  source?.scrollIntoView({ block: 'nearest' });
}
</script>
