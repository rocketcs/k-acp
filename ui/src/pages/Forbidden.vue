<template>
  <main class="forbidden-page">
    <h1>无访问权限</h1>
    <p>查看者账号仅可通过智能体对话链接访问对话页面。</p>
  </main>
</template>

<style scoped>
.forbidden-page {
  display: grid;
  min-height: 100vh;
  place-content: center;
  padding: 24px;
  color: var(--color-text, #1f1f1f);
  text-align: center;
}

h1 {
  margin: 0 0 12px;
}

p {
  margin: 0;
  color: var(--color-text-secondary, #666);
}
</style>
