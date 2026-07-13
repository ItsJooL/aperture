<template>
  <div class="card card-pad">
    <h3 class="card-title">Invoice journey</h3>
    <p class="card-description">The normal lifecycle for a tenant invoice.</p>
    <div class="timeline" style="margin-top:1rem;">
      <div v-for="(step, index) in steps" :key="step.status" class="timeline-item">
        <div class="timeline-dot">{{ index + 1 }}</div>
        <div>
          <strong>{{ step.label }}</strong>
          <p class="muted" style="margin:.2rem 0 0;">{{ step.description }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
const steps = [
  { status: 'draft', label: 'Draft', description: 'Build an invoice from a customer and billable line items.' },
  { status: 'issued', label: 'Issued', description: 'Send or publish it to the customer workspace.' },
  { status: 'paid', label: 'Paid', description: 'Record payments and reconcile outstanding amounts.' },
]
</script>
