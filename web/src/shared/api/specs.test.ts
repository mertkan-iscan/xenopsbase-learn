/**
 * The committed OpenAPI descriptions, checked for the two things a diff does not make obvious
 * (T-9.10, #88).
 *
 * `api:check` proves the checked-in spec matches the running service. It cannot tell you the
 * service is describing itself WRONGLY — both sides agree, the check is green, and the description
 * is still false. Both defects below were live for a regeneration before anybody looked at the
 * JSON:
 *
 *  1. declaring any `@ApiResponse` on a handler REPLACES the success response springdoc infers
 *     from the return type, so three operations documented their refusals and no success at all;
 *  2. an `@ApiResponse` with no `content` falls back to the method's return type, so the 404 that
 *     must carry no body — the disclosure rule, T-2.4 — was documented as returning a
 *     `PlaybackTokenView`, under a description saying it had no body.
 *
 * Reading the specs from disk keeps this fast and offline: it is the one check here that does not
 * need three services and a Keycloak.
 */
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const apiDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'api');
const services = ['identity', 'streaming', 'reporting'] as const;

type Response = { content?: Record<string, { schema?: { $ref?: string } }> };
type Operation = { responses?: Record<string, Response> };
type PathItem = Record<string, Operation>;
type Spec = {
  paths?: Record<string, PathItem>;
  components?: { schemas?: Record<string, { properties?: Record<string, unknown> }> };
};

const METHODS = ['get', 'post', 'put', 'patch', 'delete'];

function spec(service: string): Spec {
  return JSON.parse(readFileSync(join(apiDir, `${service}-openapi.json`), 'utf8')) as Spec;
}

function operations(document: Spec): [string, Operation][] {
  return Object.entries(document.paths ?? {}).flatMap(([path, item]) =>
    Object.entries(item)
      .filter(([method]) => METHODS.includes(method))
      .map(([method, operation]): [string, Operation] => [`${method.toUpperCase()} ${path}`, operation]),
  );
}

describe.each(services)('%s', (service) => {
  it('declares the problem document, with the code a client switches on', () => {
    const problem = spec(service).components?.schemas?.Problem;

    expect(problem, 'no Problem schema: a generated client cannot type a failure').toBeDefined();
    expect(Object.keys(problem?.properties ?? {})).toEqual(
      expect.arrayContaining(['type', 'title', 'status', 'detail', 'code']),
    );
  });

  it('never documents an operation that only fails', () => {
    const withoutSuccess = operations(spec(service))
      .filter(([, operation]) => !Object.keys(operation.responses ?? {}).some((code) => code.startsWith('2')))
      .map(([name]) => name);

    expect(withoutSuccess).toEqual([]);
  });

  it('answers every documented refusal with the one error shape, or with nothing', () => {
    // "Or with nothing" is not a loophole. A refusal that must not describe itself answers an
    // empty body on purpose, and the only wrong answer is a refusal carrying some OTHER shape --
    // which is what a forgotten `content = @Content` produces, silently.
    const wrong: string[] = [];
    for (const [name, operation] of operations(spec(service))) {
      for (const [code, response] of Object.entries(operation.responses ?? {})) {
        if (Number(code) < 400 || !response.content) {
          continue;
        }
        for (const [mediaType, body] of Object.entries(response.content)) {
          if (mediaType !== 'application/problem+json' || body.schema?.$ref !== '#/components/schemas/Problem') {
            wrong.push(`${name} ${code}: ${mediaType} ${body.schema?.$ref ?? '(inline schema)'}`);
          }
        }
      }
    }

    expect(wrong).toEqual([]);
  });
});
