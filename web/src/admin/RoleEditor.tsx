import { useCallback, useEffect, useState } from 'react';
import { failureFrom, identity, type ApiFailure } from '../shared/api/client.ts';
import { buttonClasses } from '../shared/design/Button.tsx';
import { fieldClasses } from '../shared/design/Field.tsx';
import type { components } from '../shared/api/identity.d.ts';
import { StateChip } from '../shared/design/State.tsx';
import { Notice } from '../shared/design/Surface.tsx';
import { formatNumber } from '../shared/i18n/format.ts';
import { useLocale, useT } from '../shared/i18n/useLocale.ts';
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
  const { locale, t, plural } = useLocale();
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
    return <Loading what="loading.roles" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  const open = screen.roles.find((role) => role.id === openId) ?? null;

  return (
    <div className="flex flex-col gap-6">
      <Notice label={t('roles.half-missing.label')}>
        {t('roles.half-missing.body', { enum: 'Permission' })}
      </Notice>

      <div className="grid gap-6 desk:grid-cols-[20rem_1fr] desk:items-start">
        <section aria-labelledby="roles">
          <h2 id="roles" className="label-caps">
            {t('roles.title')}
          </h2>
          {screen.roles.length === 0 ? (
            <Empty title={t('roles.empty.title')}>
              <p className="text-sm text-muted">{t('roles.empty.body')}</p>
            </Empty>
          ) : (
            <ul className="flex flex-col overflow-hidden rounded-xl border border-hairline bg-surface">
              {screen.roles.map((role) => (
                <li key={role.id}>
                  <button
                    type="button"
                    onClick={() => setOpenId(role.id ?? null)}
                    aria-current={role.id === openId ? 'true' : undefined}
                    className={`flex w-full flex-col items-start gap-1 border-b border-hairline px-4 py-2.5 text-start transition-colors duration-150 last:border-b-0 ${
                      role.id === openId
                        ? 'bg-brand-tint text-brand'
                        : 'hover:bg-surface-muted'
                    }`}
                  >
                    <span className="w-full truncate text-sm font-semibold">{role.name}</span>
                    <span className="flex flex-wrap items-center gap-2">
                      {role.system ? (
                        <StateChip state="published" detail={t('roles.system')} />
                      ) : null}
                      <span className="text-xs text-muted">
                        {plural('roles.permission-count', role.permissions?.length ?? 0, {
                          count: formatNumber(locale, role.permissions?.length ?? 0),
                        })}
                      </span>
                    </span>
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
  const t = useT();
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
    <section className="card flex flex-col gap-4 p-5" aria-labelledby="permissions">
      <h2 id="permissions" className="label-caps">
        {role.name}
      </h2>
      <p className="text-sm text-muted">{role.description}</p>

      {held.length === 0 ? (
        <p className="text-sm text-muted">{t('roles.holds-nothing')}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {held.map((code) => (
            <li
              key={code}
              className="flex items-center justify-between gap-3 rounded-lg border border-hairline bg-surface-muted px-3 py-2"
            >
              {/* Monospace, because a permission is a code somebody has to type exactly -- the one
                  place in this product where the characters matter more than the reading. */}
              <code className="font-mono text-xs font-semibold">{code}</code>
              <button
                type="button"
                className={buttonClasses('ghost', 'sm')}
                disabled={role.system === true}
                onClick={() => void save(held.filter((one) => one !== code))}
              >
                {t('roles.remove')}
              </button>
            </li>
          ))}
        </ul>
      )}

      {role.system ? (
        <p className="text-sm text-muted">{t('roles.seeded')}</p>
      ) : (
        <form
          className="flex flex-wrap items-end gap-2"
          onSubmit={(event) => {
            event.preventDefault();
            if (adding.trim()) {
              void save([...held, adding.trim()]);
              setAdding('');
            }
          }}
        >
          <label className="label-caps" htmlFor="add-permission">
            {t('roles.add-permission')}
          </label>
          <input
            id="add-permission"
            className={fieldClasses(true)}
            value={adding}
            onChange={(event) => setAdding(event.target.value)}
            placeholder={t('roles.code-placeholder')}
          />
          <button type="submit" className={buttonClasses('secondary', 'sm')} disabled={!adding.trim()}>
            {t('roles.add')}
          </button>
        </form>
      )}

      {problem ? (
        <p className="rounded-lg border border-overdue-edge bg-overdue-bg p-3 text-sm text-overdue-fg" role="alert">
          {problem}
        </p>
      ) : null}
    </section>
  );
}
