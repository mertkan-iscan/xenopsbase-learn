import { useCallback, useEffect, useState } from 'react';
import { failureFrom, identity, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/identity.d.ts';
import { StateChip } from '../shared/design/State.tsx';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';

type RoleView = components['schemas']['RoleView'];

/**
 * Roles — real, and honest about the half that is missing (T-10.4, T-2.7).
 *
 * <p>Roles themselves are entirely real: they can be listed, created, cloned, renamed and given a
 * set of permissions, and this screen does all of it.
 *
 * <p><b>What does not exist is the catalogue of permissions to choose from.</b> The seventeen codes
 * live in the `Permission` enum in identity, are projected into a table by
 * `PermissionCatalogSeeder`, and are published to integrators as a Markdown table inside the
 * OpenAPI description — but no endpoint returns them. So the picker the design asked for, grouped
 * by resource with a sentence each, has no runtime source.
 *
 * <p>The screen therefore does the one thing that stays true as the catalogue changes: it shows
 * what each role actually holds, lets a code be added or removed, and reports the server's own
 * refusal when a code is not in the catalogue. It does <b>not</b> hard-code seventeen strings and
 * present them as the list — that copy would go stale silently, and a permission picker that is
 * quietly wrong is how somebody grants nothing and believes they granted something.
 */
type Screen =
  | { status: 'loading' }
  | { status: 'ready'; roles: RoleView[] }
  | { status: 'failed'; failure: ApiFailure };

export function RoleEditor() {
  const [screen, setScreen] = useState<Screen>({ status: 'loading' });
  const [openId, setOpenId] = useState<string | null>(null);

  const load = useCallback(() => {
    identity
      .GET('/api/v1/roles')
      .then(({ data, response, error }) => {
        setScreen(
          data
            ? { status: 'ready', roles: data }
            : { status: 'failed', failure: failureFrom(response, error) },
        );
      })
      .catch((unreachable: unknown) => {
        setScreen({ status: 'failed', failure: failureFrom(undefined, unreachable) });
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (screen.status === 'loading') {
    return <Loading what="roles" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  const open = screen.roles.find((role) => role.id === openId) ?? null;

  return (
    <div className="role-page">
      <p className="not-enforced" role="note">
        <span className="u-caps">Half of this is missing</span>
        <span>
          Roles are real. The list of permissions to choose from is not published by any endpoint —
          it lives in identity’s <code>Permission</code> enum and reaches integrators only as a
          table in the API description. Codes are typed here and validated by the server.
        </span>
      </p>

      <div className="role-page__split">
        <section aria-labelledby="roles">
          <h2 id="roles" className="u-caps">
            Roles
          </h2>
          {screen.roles.length === 0 ? (
            <Empty title="This company has no roles.">
              <p className="u-meta">The seeded ones arrive with the tenant.</p>
            </Empty>
          ) : (
            <ul className="panel role-page__list">
              {screen.roles.map((role) => (
                <li key={role.id}>
                  <button
                    type="button"
                    className={role.id === openId ? 'node node--on' : 'node'}
                    onClick={() => setOpenId(role.id ?? null)}
                  >
                    {role.name}
                    {role.system ? <StateChip state="published" detail="system" /> : null}
                    <span className="u-meta"> {role.permissions?.length ?? 0} permissions</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </section>

        {/*
          * `key` rather than an effect that copies the prop into state. Choosing another role
          * remounts this, which resets the pending edit and the error together -- carrying one
          * role's refusal onto the next one is exactly the bug syncing in an effect produces.
          */}
        {open ? <RolePermissions key={open.id} role={open} onChanged={load} /> : null}
      </div>
    </div>
  );
}

function RolePermissions({ role, onChanged }: { role: RoleView; onChanged: () => void }) {
  const [held, setHeld] = useState<string[]>(role.permissions ?? []);
  const [adding, setAdding] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  async function save(next: string[]) {
    if (!role.id) {
      return;
    }
    // The whole set is replaced, not patched -- so `next` must always be the complete list.
    const { response, error } = await identity.PUT('/api/v1/roles/{id}/permissions', {
      params: { path: { id: role.id } },
      body: { permissions: next },
    });
    if (error || !response?.ok) {
      // The server knows the catalogue even though it will not publish it, and refuses an unknown
      // code by name. That refusal is the only authority on what exists, so it is shown verbatim.
      setProblem(failureFrom(response, error).message);
      return;
    }
    setHeld(next);
    setProblem(null);
    onChanged();
  }

  return (
    <section className="role-page__permissions panel" aria-labelledby="permissions">
      <h2 id="permissions" className="u-caps">
        {role.name}
      </h2>
      <p className="u-meta">{role.description}</p>

      {held.length === 0 ? (
        <p className="u-meta">This role holds nothing, so it grants nothing.</p>
      ) : (
        <ul className="role-page__held">
          {held.map((code) => (
            <li key={code}>
              <code>{code}</code>
              <button
                type="button"
                className="btn btn-ghost btn-dense"
                disabled={role.system === true}
                onClick={() => void save(held.filter((one) => one !== code))}
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}

      {role.system ? (
        <p className="u-meta">A seeded role. Clone it rather than changing what every tenant gets.</p>
      ) : (
        <form
          className="role-page__add"
          onSubmit={(event) => {
            event.preventDefault();
            if (adding.trim()) {
              void save([...held, adding.trim()]);
              setAdding('');
            }
          }}
        >
          <label className="u-caps" htmlFor="add-permission">
            Add a permission
          </label>
          <input
            id="add-permission"
            className="input input-dense"
            value={adding}
            onChange={(event) => setAdding(event.target.value)}
            placeholder="resource:action"
          />
          <button type="submit" className="btn btn-secondary" disabled={!adding.trim()}>
            Add
          </button>
        </form>
      )}

      {problem ? (
        <p className="authoring__short" role="alert">
          {problem}
        </p>
      ) : null}
    </section>
  );
}
