<template>
  <section class="card card-pad">
    <div class="flex-between">
      <div>
        <p class="page-kicker">Invoice total</p>
        <h3 class="stat-value">{{ currency(total) }}</h3>
      </div>
      <AppBadge variant="success">{{ lines.length }} lines</AppBadge>
    </div>
    <div class="table-wrap" style="margin-top:1rem;">
      <table>
        <thead><tr><th>Description</th><th>Qty</th><th>Unit</th><th>Total</th></tr></thead>
        <tbody>
          <tr v-for="(line, index) in lines" :key="index">
            <td>{{ line.description || 'Line item' }}</td>
            <td>{{ line.quantity }}</td>
            <td>{{ currency(line.unit_price) }}</td>
            <td><strong>{{ currency(line.quantity * line.unit_price) }}</strong></td>
          </tr>
          <tr v-if="!lines.length"><td colspan="4" class="muted">Add billable items to start building the invoice.</td></tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import AppBadge from '@/components/ui/AppBadge.vue'
import { currency } from '@/lib/utils'

const props = defineProps<{ lines: Array<{ description: string; quantity: number; unit_price: number }> }>()
const total = computed(() => props.lines.reduce((sum, line) => sum + line.quantity * line.unit_price, 0))
</script>
