<template>
  <svg
    class="connector-path"
    viewBox="0 0 100 80"
    preserveAspectRatio="none"
    aria-hidden="true"
  >
    <path
      :d="d"
      fill="none"
      stroke="var(--home-border)"
      stroke-width="1.5"
      stroke-linecap="round"
      stroke-linejoin="round"
      vector-effect="non-scaling-stroke"
    />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ right?: boolean }>()

// Squared-off C/U shape with 4 rounded corners (radius r).
// Enters vertically at (50,0) and exits vertically at (50,80).
// Arms extend to x=arm_x (right) or x=arm_x (left).
const d = computed(() => {
  const r   = 8                       // corner radius in viewBox units
  const arm = props.right ? 82 : 18   // horizontal extent

  if (props.right) {
    return [
      'M 50,0',
      `L 50,${20 - r}`,              // straight down to top-left corner
      `Q 50,20 ${50 + r},20`,        // top-left: down → right
      `L ${arm - r},20`,             // top horizontal
      `Q ${arm},20 ${arm},${20 + r}`,// top-right: right → down
      `L ${arm},${60 - r}`,          // right wall (straight)
      `Q ${arm},60 ${arm - r},60`,   // bottom-right: down → left
      `L ${50 + r},60`,              // bottom horizontal
      `Q 50,60 50,${60 + r}`,        // bottom-left: left → down
      'L 50,80',                     // straight down to exit
    ].join(' ')
  } else {
    return [
      'M 50,0',
      `L 50,${20 - r}`,
      `Q 50,20 ${50 - r},20`,        // top-right: down → left
      `L ${arm + r},20`,
      `Q ${arm},20 ${arm},${20 + r}`,// top-left: left → down
      `L ${arm},${60 - r}`,
      `Q ${arm},60 ${arm + r},60`,   // bottom-left: down → right
      `L ${50 - r},60`,
      `Q 50,60 50,${60 + r}`,        // bottom-right: right → down
      'L 50,80',
    ].join(' ')
  }
})
</script>

<style scoped>
.connector-path {
  display: block;
  width: 100%;
  height: 80px;
}
@media (max-width: 640px) {
  .connector-path { display: none; }
}
</style>
