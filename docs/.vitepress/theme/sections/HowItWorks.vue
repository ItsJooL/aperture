<template>
  <section class="how">
    <p class="section-label">How it works</p>
    <h2>Four steps from model to production</h2>
    <p class="section-sub">One manifest file. Everything else is generated.</p>

    <div class="steps">

      <div class="step">
        <div class="step-text">
          <div class="step-num-wrap">
            <div class="step-num">1</div>
            <span class="step-label">Define your model</span>
          </div>
          <h3>Describe your domain in YAML</h3>
          <p>Declare entities, fields, types, and relationships. Mark entities as tenant-scoped. This manifest is the single source of truth, with no Java or Spring config.</p>
        </div>
        <CodeWindow filename="invoice.yaml" :code="step1Code" />
      </div>

      <div class="step flip">
        <div class="step-text">
          <div class="step-num-wrap">
            <div class="step-num">2</div>
            <span class="step-label">Secure it</span>
          </div>
          <h3>Declare permissions and policies</h3>
          <p>Define role-based access per operation and attribute-based policies inline. Aperture enforces them at runtime from your manifest, so there is no separate security layer.</p>
        </div>
        <CodeWindow filename="invoice.yaml" :code="step2Code" />
      </div>

      <div class="step">
        <div class="step-text">
          <div class="step-num-wrap">
            <div class="step-num">3</div>
            <span class="step-label">Hook into the lifecycle</span>
          </div>
          <h3>Attach validation and triggers</h3>
          <p>Four hook types fire at the right phase of every request. You own the logic over HTTP while Aperture handles signing, retries, and failure modes.</p>
        </div>
        <CodeWindow filename="invoice.yaml" :code="step3Code" />
      </div>

      <div class="step flip">
        <div class="step-text">
          <div class="step-num-wrap">
            <div class="step-num">4</div>
            <span class="step-label">Build and ship</span>
          </div>
          <h3>Run the build. Deploy. Done.</h3>
          <p>The Maven plugin generates all Java source and Liquibase migrations. No code written by hand, no SQL to manage. Commit the lock files and ship.</p>
        </div>
        <CodeWindow filename="terminal" :code="step4Code" />
      </div>

    </div>
  </section>
</template>

<script setup lang="ts">
import CodeWindow from '../components/CodeWindow.vue'

const step1Code = `apiVersion: aperture.itsjool.com/v1
kind: Entity
metadata:
  name: Invoice
spec:
  tenantScoped: true
  fields:
    amount:
      type: decimal
      required: true
    status:
      type: string
      enum: [DRAFT, ISSUED, PAID]
    customer:
      type: ref
      target: Customer
      relation: ManyToOne
      required: true`

const step2Code = `  permissions:
    TenantAdmin: [read, delete]
    Accountant:  [create, read, update]
    Viewer:      [read]

  policies:
    FinanceTeamOnly: [read, update]
    EuRegionOnly:    [read, update]`

const step3Code = `  hooks:
    ValidateInvoice:
      phase: PRECOMMIT
      async: false
      onFailure: reject
      url: http://hook-service:8080/hooks/validate-invoice`

const step4Code = `# generates, migrates, tests, packages
$ mvn verify

✓ Manifest validated
✓ Java source generated
✓ Liquibase changeset written
✓ 81 tests passed

$ docker compose up --detach
✓ JSON:API server listening on :8080`
</script>

<style scoped>
.how {
  background: var(--home-bg);
  padding: 88px 48px;
}
.section-label {
  text-align: center; color: var(--home-accent);
  font-size: 12px; font-weight: 700; letter-spacing: 1.2px;
  text-transform: uppercase; margin-bottom: 10px;
}
h2 {
  font-size: 34px; font-weight: 800; letter-spacing: -1px;
  text-align: center; color: var(--home-text); margin-bottom: 10px;
}
.section-sub { text-align: center; color: var(--home-muted); font-size: 15px; margin-bottom: 60px; }
.steps { max-width: 860px; margin: 0 auto; display: flex; flex-direction: column; gap: 64px; }
.step { display: grid; grid-template-columns: 1fr 1fr; gap: 48px; align-items: center; }
.step.flip { direction: rtl; }
.step.flip > * { direction: ltr; }
.step-num-wrap { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.step-num {
  width: 26px; height: 26px; border-radius: 50%;
  background: var(--home-accent); color: white;
  font-size: 11px; font-weight: 800;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.step-label { font-size: 11px; font-weight: 600; color: var(--home-muted); letter-spacing: .5px; text-transform: uppercase; }
h3 { font-size: 19px; font-weight: 700; margin-bottom: 10px; letter-spacing: -0.3px; }
p { color: var(--home-text-2); font-size: 14px; line-height: 1.65; }

@media (max-width: 640px) {
  .how { padding: 64px 20px; }
  h2 { font-size: 28px; }
  .section-sub { margin-bottom: 44px; }
  .steps { gap: 48px; }
  .step {
    grid-template-columns: minmax(0, 1fr);
    gap: 24px;
  }
  .step.flip { direction: ltr; }
}
</style>
