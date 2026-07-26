<template>
  <footer class="site-filing-footer">
    <a
      href="https://beian.miit.gov.cn/"
      target="_blank"
      rel="noopener noreferrer"
      aria-label="工业和信息化部 ICP 备案查询"
    >
      蜀ICP备2026041718号
    </a>
  </footer>
</template>

<style scoped>
.site-filing-footer {
  display: flex;
  justify-content: center;
  padding: 10px 24px 16px;
  color: var(--yb-text-secondary);
  font-size: 13px;
  line-height: 20px;
}

.site-filing-footer a {
  color: inherit;
  text-decoration: none;
}

.site-filing-footer a:hover,
.site-filing-footer a:focus-visible {
  color: var(--yb-link);
  text-decoration: underline;
}
</style>
