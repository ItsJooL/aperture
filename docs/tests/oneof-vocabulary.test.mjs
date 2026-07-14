import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

const docsRoot = resolve(import.meta.dirname, '..');
const files = [
  'guide/build-deploy.md',
  'guide/cli.md',
  'guide/core-concepts.md',
  'guide/manifests.md',
  'guide/multi-tenancy.md',
  'reference/configuration.md',
  'reference/manifest-schema.md',
  'reference/rest-api.md',
  'examples/billing-demo.md',
];

for (const file of files) {
  const content = readFileSync(resolve(docsRoot, file), 'utf8');
  assert(!/\b(polymorph\w*|inheritance|discriminator)\b/i.test(content),
    `${file} uses internal inheritance vocabulary for the public OneOf model`);
}
