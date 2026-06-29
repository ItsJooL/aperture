<template>
  <div class="elbow" :class="{ up }">
    <div class="el-r"/><div class="el-c"/><div class="el-l"/>
  </div>
</template>

<script setup lang="ts">
defineProps<{ up?: boolean }>()
</script>

<style scoped>
.elbow { height: 44px; display: flex; overflow: hidden; }
.el-r {
  flex: 1;
  border-right: 1.5px solid var(--home-border);
  border-bottom: 1.5px solid var(--home-border);
  border-radius: 0 0 10px 0;
}
.el-c { width: 80px; flex-shrink: 0; }
.el-l {
  flex: 1;
  border-left: 1.5px solid var(--home-border);
  border-bottom: 1.5px solid var(--home-border);
  border-radius: 0 0 0 10px;
}
.up .el-r {
  border-bottom: none; border-right: none;
  border-top: 1.5px solid var(--home-border);
  border-left: 1.5px solid var(--home-border);
  border-radius: 10px 0 0 0;
}
.up .el-l {
  border-bottom: none; border-left: none;
  border-top: 1.5px solid var(--home-border);
  border-right: 1.5px solid var(--home-border);
  border-radius: 0 10px 0 0;
}
</style>
