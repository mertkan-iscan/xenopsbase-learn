/**
 * The typed API client, generated from the service's own OpenAPI description (T-10.1).
 *
 *   node scripts/api.mjs generate   # refresh the checked-in spec and types
 *   node scripts/api.mjs check      # fail if they have drifted from the running service
 *
 * WHY THE SPEC IS CHECKED IN AND ALSO CHECKED
 *
 * Checked in, so a build does not need a running backend and a diff shows what changed to the
 * person changing it. Checked, so the copy cannot quietly become fiction: `check` regenerates
 * against a live service and fails on any difference. That is the criterion this task exists to
 * satisfy -- a backend change that breaks the frontend has to fail a build rather than a screen.
 *
 * `check` fails when it cannot reach the service, deliberately. A check that passes because it
 * had nothing to compare against is worse than no check: it reports success for work it did not
 * do, which is exactly the state this is meant to detect.
 */
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import openapiTS, { astToString } from 'openapi-typescript';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');

const services = [
  {
    name: 'identity',
    url: process.env.IDENTITY_URL ?? 'http://localhost:8082',
    spec: join(root, 'api', 'identity-openapi.json'),
    types: join(root, 'src', 'shared', 'api', 'identity.d.ts'),
  },
  {
    // The player mints its own playback tokens (T-3.4), so `streaming` is a second service the
    // browser talks to and a second contract that can drift. One list, so adding a service is
    // adding an entry rather than remembering there is a check to extend (T-3.5).
    name: 'streaming',
    url: process.env.STREAMING_URL ?? 'http://localhost:8083',
    spec: join(root, 'api', 'streaming-openapi.json'),
    types: join(root, 'src', 'shared', 'api', 'streaming.d.ts'),
  },
  {
    // The player posts heartbeats here and nowhere else (T-3.6). It is a third service the
    // browser talks to directly, and deliberately so: the write-heavy path must not pass through
    // anything a learner's playback depends on.
    name: 'reporting',
    url: process.env.REPORTING_URL ?? 'http://localhost:8084',
    spec: join(root, 'api', 'reporting-openapi.json'),
    types: join(root, 'src', 'shared', 'api', 'reporting.d.ts'),
  },
];

const mode = process.argv[2];
if (mode !== 'generate' && mode !== 'check') {
  console.error('usage: node scripts/api.mjs generate|check');
  process.exit(2);
}

let drifted = false;

for (const service of services) {
  const live = await fetchSpec(service);

  if (mode === 'generate') {
    mkdirSync(dirname(service.spec), { recursive: true });
    writeFileSync(service.spec, live, 'utf8');
    await writeTypes(service, live);
    console.log(`generated ${service.name}: ${service.spec}`);
    continue;
  }

  const checkedIn = read(service.spec);
  if (checkedIn !== live) {
    drifted = true;
    console.error(
      `\n${service.name}: the checked-in OpenAPI description does not match the running service.\n` +
      `  ${service.spec}\n\n` +
      'The service changed and this client did not. Run `npm run api:generate`, look at the diff,\n' +
      'and fix whatever the change broke -- that diff is the point of this check.\n',
    );
  } else {
    console.log(`${service.name}: client matches the service`);
  }
}

process.exit(drifted ? 1 : 0);

async function fetchSpec(service) {
  const endpoint = `${service.url}/v3/api-docs`;
  let response;
  try {
    response = await fetch(endpoint);
  } catch (unreachable) {
    console.error(
      `\nCannot reach ${service.name} at ${endpoint}: ${unreachable.message}\n\n` +
      'This needs the service running -- `make up`, then start it. Failing rather than skipping,\n' +
      'because a check that passes with nothing to compare against reports success for work it\n' +
      'did not do.\n',
    );
    process.exit(1);
  }
  if (!response.ok) {
    console.error(`${service.name}: ${endpoint} answered ${response.status}`);
    process.exit(1);
  }
  // Re-serialised with sorted keys and two-space indent so the checked-in file is stable: the
  // diff has to mean "the API changed", not "the JSON writer felt different today".
  return `${JSON.stringify(sortKeys(await response.json()), null, 2)}\n`;
}

// Through openapi-typescript's own API rather than by spawning its CLI: `npx` is a shell script
// on one platform and a .cmd on another, and a generator that only runs on the machine it was
// written on is a generator nobody else regenerates.
async function writeTypes(service, spec) {
  mkdirSync(dirname(service.types), { recursive: true });
  const ast = await openapiTS(JSON.parse(spec));
  writeFileSync(service.types, `${astToString(ast)}`, 'utf8');
}

function read(path) {
  try {
    return readFileSync(path, 'utf8');
  } catch {
    return '';
  }
}

function sortKeys(value) {
  if (Array.isArray(value)) {
    return value.map(sortKeys);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value).sort().map((key) => [key, sortKeys(value[key])]),
    );
  }
  return value;
}
