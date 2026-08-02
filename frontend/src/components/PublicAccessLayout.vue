<template>
  <main class="public-access">
    <section class="public-access__intro">
      <slot name="intro" />
    </section>
    <section class="public-access__form">
      <slot />
    </section>
    <section v-if="$slots.below" class="public-access__below">
      <slot name="below" />
    </section>
  </main>
</template>
