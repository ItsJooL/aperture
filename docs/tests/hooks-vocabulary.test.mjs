import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

const docsRoot = resolve(import.meta.dirname, '..');
const files = [
  '../README.md',
  'guide/hooks.md',
  'guide/manifests.md',
  'guide/core-concepts.md',
  'reference/manifest-schema.md',
  'examples/billing-demo.md',
];

for (const file of files) {
  const content = readFileSync(resolve(docsRoot, file), 'utf8');
  assert(!/^\s+phase:\s/m.test(content), `${file} still documents hook phase manifest fields`);
  assert(!/^\s+async:\s+(true|false)\s*$/m.test(content), `${file} still documents hook async manifest fields`);
}

const hookGuide = readFileSync(resolve(docsRoot, 'guide/hooks.md'), 'utf8');
assert.match(hookGuide, /type: validate/);
assert.match(hookGuide, /type: mutate/);
assert.match(hookGuide, /type: trigger/);
assert.match(hookGuide, /type: guard/);
