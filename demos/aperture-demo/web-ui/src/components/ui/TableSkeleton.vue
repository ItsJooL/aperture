<template>
  <div class="table-wrap" aria-label="Loading rows">
    <table>
      <thead><tr><th v-for="column in columns" :key="column">{{ column }}</th></tr></thead>
      <tbody>
        <tr v-for="row in rows" :key="row">
          <td v-for="column in columns" :key="column"><div class="skeleton skeleton-row" /></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ columns?: string[]; rows?: number }>(), {
  columns: () => ['Name', 'Status', 'Updated'],
  rows: 5,
})
</script>
