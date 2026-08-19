<template>
  <div
    v-if="items.length"
    ref="railRef"
    class="chat-minimap-rail"
    @mouseenter="cancelHoverClear"
    @mouseleave="scheduleHoverClear"
  >
    <div class="chat-minimap" :aria-label="ariaLabel" @scroll="syncPreviewPosition">
      <button
        v-for="(item, index) in items"
        :key="item.id"
        :ref="(element) => setItemRef(element, item.id)"
        type="button"
        class="chat-minimap__item chat-minimap__item--user"
        :class="[
          item.state ? `chat-minimap__item--${item.state}` : '',
          waveClass(index),
          { 'chat-minimap__item--active': activeId === item.id },
        ]"
        :aria-label="item.user"
        :aria-current="activeId === item.id ? 'location' : undefined"
        @mouseenter="setHoveredIndex(index)"
        @focus="setHoveredIndex(index)"
        @blur="scheduleHoverClear"
        @click="emit('select', item.id)"
      />
    </div>
    <div
      v-if="hoveredItem"
      ref="previewRef"
      class="chat-minimap__preview"
      :style="{ top: `${previewTop}px` }"
      @mouseenter="cancelHoverClear"
      @mouseleave="scheduleHoverClear"
    >
      <div class="chat-minimap__preview-line chat-minimap__preview-line--user">
        {{ hoveredItem.user }}
      </div>
      <div
        v-if="hoveredItem.assistant"
        class="chat-minimap__preview-line chat-minimap__preview-line--assistant"
      >
        {{ hoveredItem.assistant }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';

export interface ConversationQuestionNavigationItem {
  id: string;
  user: string;
  assistant?: string;
  state?: 'queued' | 'running' | 'waiting_user' | 'succeeded' | 'failed' | 'cancelled';
}

const props = withDefaults(defineProps<{
  items: ConversationQuestionNavigationItem[];
  activeId?: string | null;
  ariaLabel?: string;
}>(), {
  activeId: null,
  ariaLabel: '当前会话问题导航',
});
const emit = defineEmits<{ select: [id: string] }>();

const railRef = ref<HTMLElement | null>(null);
const previewRef = ref<HTMLElement | null>(null);
const itemRefs: Record<string, HTMLElement | null> = {};
const hoveredIndex = ref<number | null>(null);
const previewTop = ref(8);
let hoverClearTimer: number | null = null;

const hoveredItem = computed(() => (
  hoveredIndex.value == null ? null : props.items[hoveredIndex.value] ?? null
));

watch(() => props.items.map((item) => item.id).join('\0'), () => {
  hoveredIndex.value = null;
  Object.keys(itemRefs).forEach((id) => {
    if (!props.items.some((item) => item.id === id)) delete itemRefs[id];
  });
});

onBeforeUnmount(cancelHoverClear);

function setItemRef(element: unknown, id: string) {
  if (element instanceof HTMLElement) itemRefs[id] = element;
  else delete itemRefs[id];
}

function setHoveredIndex(index: number) {
  cancelHoverClear();
  hoveredIndex.value = index;
  void nextTick(syncPreviewPosition);
}

function cancelHoverClear() {
  if (hoverClearTimer == null) return;
  window.clearTimeout(hoverClearTimer);
  hoverClearTimer = null;
}

function scheduleHoverClear() {
  cancelHoverClear();
  hoverClearTimer = window.setTimeout(() => {
    hoveredIndex.value = null;
    hoverClearTimer = null;
  }, 90);
}

function syncPreviewPosition() {
  const item = hoveredItem.value;
  const rail = railRef.value;
  const preview = previewRef.value;
  const marker = item ? itemRefs[item.id] : null;
  if (!item || !rail || !preview || !marker) {
    previewTop.value = 8;
    return;
  }
  const railRect = rail.getBoundingClientRect();
  const markerRect = marker.getBoundingClientRect();
  const center = markerRect.top - railRect.top + markerRect.height / 2;
  const minTop = 8;
  const maxTop = Math.max(minTop, rail.clientHeight - preview.offsetHeight - 8);
  previewTop.value = Math.min(Math.max(center - preview.offsetHeight / 2, minTop), maxTop);
}

function waveClass(index: number) {
  if (hoveredIndex.value == null) return '';
  const distance = Math.abs(index - hoveredIndex.value);
  return distance <= 3 ? `chat-minimap__item--wave-${distance}` : '';
}
</script>
