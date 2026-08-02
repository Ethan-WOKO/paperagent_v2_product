<template>
  <svg class="app-nav-icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">
    <template v-if="name === 'workspace'">
      <path d="M5 5.25h14a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-7l-4.5 3v-3H5a2 2 0 0 1-2-2v-8.5a2 2 0 0 1 2-2z" />
      <path d="M7.5 9.25h9M7.5 13.25h5.5" />
    </template>
    <template v-else-if="name === 'paper'">
      <path d="M7 3.75h7.5L18.25 7.5v12.75H7z" />
      <path d="M14.5 3.75V7.5h3.75M9.5 11h6M9.5 14h6M9.5 17h4" />
    </template>
    <template v-else-if="name === 'projects'">
      <rect x="3.75" y="4.5" width="16.5" height="15" rx="2" />
      <path d="M8.25 4.5v15M8.25 9.5h12M12.25 12.75h4.5M12.25 16h3" />
    </template>
    <template v-else-if="name === 'knowledge'">
      <ellipse cx="12" cy="6.25" rx="7.75" ry="3" />
      <path d="M4.25 6.25v5.5c0 1.65 3.47 3 7.75 3s7.75-1.35 7.75-3v-5.5M4.25 11.75v5.5c0 1.65 3.47 3 7.75 3s7.75-1.35 7.75-3v-5.5" />
    </template>
    <template v-else-if="name === 'search'">
      <circle cx="10.25" cy="10.25" r="6.25" />
      <path d="m15 15 5 5M7.75 8.5h5M7.75 11.5h3.25" />
    </template>
    <template v-else-if="name === 'memory'">
      <path d="M8 5.25a4 4 0 0 1 7.75 1.4 4.15 4.15 0 0 1 1.95 7.7A4 4 0 0 1 12 19.5a4 4 0 0 1-5.7-5.15A4.15 4.15 0 0 1 8 6.65z" />
      <path d="M9.25 8.25c1.65.2 2.75 1.2 2.75 3v8.25M14.75 8.25c-1.65.2-2.75 1.2-2.75 3M7.25 14.25c1.45-.1 2.45.4 3 1.5M16.75 14.25c-1.45-.1-2.45.4-3 1.5" />
    </template>
    <template v-else-if="name === 'settings'">
      <circle cx="12" cy="12" r="2.75" />
      <path d="M19.25 13.1v-2.2l-2.1-.75a7 7 0 0 0-.65-1.55l.95-2-1.55-1.55-2 .95a7 7 0 0 0-1.55-.65l-.75-2.1H9.4l-.75 2.1A7 7 0 0 0 7.1 6l-2-.95L3.55 6.6l.95 2a7 7 0 0 0-.65 1.55l-2.1.75v2.2l2.1.75c.15.55.37 1.07.65 1.55l-.95 2 1.55 1.55 2-.95c.48.28 1 .5 1.55.65l.75 2.1h2.2l.75-2.1a7 7 0 0 0 1.55-.65l2 .95 1.55-1.55-.95-2c.28-.48.5-1 .65-1.55z" />
    </template>
    <template v-else>
      <path d="M12 3.5 19 6v5.2c0 4.45-2.7 7.55-7 9.3-4.3-1.75-7-4.85-7-9.3V6z" />
      <path d="M9 12.1 11 14l4.25-4.25" />
    </template>
  </svg>
</template>

<script setup lang="ts">
defineProps<{ name: string }>();
</script>
