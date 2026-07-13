import { readFileSync, readdirSync, statSync } from 'node:fs';
import { resolve, join } from 'node:path';
import assert from 'node:assert/strict';

const docsRoot = resolve(import.meta.dirname, '..');
const repoRoot = resolve(docsRoot, '..');

// Docs-site pages that describe hook manifest syntax or hook vocabulary directly.
const docFiles = [
  '../README.md',
  'guide/hooks.md',
  'guide/manifests.md',
  'guide/core-concepts.md',
  'guide/observability.md',
  'reference/manifest-schema.md',
  'examples/billing-demo.md',
].map((file) => resolve(docsRoot, file));

// Every markdown file under demos/** — demo READMEs and Bruno-collection READMEs
// are just as reader-facing as the docs site, and have gone stale before (a
// renamed .bru file, a retired hook-phase name) with nothing catching it because
// this guard only ever scanned the docs-site pages above.
const demosRoot = resolve(repoRoot, 'demos');
const demoFiles = readdirSync(demosRoot, { recursive: true })
  .filter((entry) => entry.endsWith('.md'))
  .map((entry) => join(demosRoot, entry))
  .filter((file) => statSync(file).isFile());

const allFiles = [...docFiles, ...demoFiles];

for (const file of allFiles) {
  const content = readFileSync(file, 'utf8');
  assert(!/^\s+phase:\s/m.test(content), `${file} still documents hook phase manifest fields`);
  assert(!/^\s+async:\s+(true|false)\s*$/m.test(content), `${file} still documents hook async manifest fields`);
  // PREENRICH was the pre-migration name for what is now the `mutate` hook type;
  // the generator has not emitted it in a long time. Its Bruno fixture was
  // renamed from `*-preenrich.bru` to `*-mutate.bru` in commit dec21fe.
  assert(!/PREENRICH/.test(content), `${file} still references the dead PREENRICH hook vocabulary`);
  assert(!/-preenrich\.bru/.test(content), `${file} still references the renamed *-preenrich.bru fixture`);
}

const hookGuide = readFileSync(resolve(docsRoot, 'guide/hooks.md'), 'utf8');
assert.match(hookGuide, /type: validate/);
assert.match(hookGuide, /type: mutate/);
assert.match(hookGuide, /type: trigger/);
assert.match(hookGuide, /type: guard/);
