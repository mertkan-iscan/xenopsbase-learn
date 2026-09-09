import { useMemo, useState } from 'react';

/**
 * The role editor (T-10.4, T-2.7) — the screen that decides whether the permission model is
 * usable.
 *
 * <p>The whole authorization design exists so a customer can build a role by picking permissions.
 * A flat list of ninety-six `resource:action` codes is not a screen anyone can use correctly, so
 * three things here are the feature rather than its presentation:
 *
 * <ol>
 *   <li><b>Grouping by resource</b>, so a person looks in one place for everything about learners.
 *   <li><b>A plain-language sentence per permission</b>, with the code beside it rather than
 *       instead of it — the sentence is for the person choosing, the code is for the person
 *       debugging, and neither can be dropped.
 *   <li><b>A preview that says what this role can and CANNOT do</b>, written from the selection as
 *       it changes. The cannot half is the half that gets left out and the half that answers the
 *       question an administrator actually has.
 * </ol>
 *
 * <p>THERE IS NO PERMISSIONS ENDPOINT YET. The catalogue below is a fixture; the live one arrives
 * with the permission surface that T-9.11 and ADR-0109 are still open on. The shape it is written
 * in — code, sentence, and the reach some of them carry — is the shape that surface has to answer
 * with, which is the useful half of building this now.
 */
export type Permission = {
  code: string;
  title: string;
  /** What it means, in words an administrator can act on. Empty only where the title says it all. */
  detail?: string;
  /** What the role can do if it holds this, in the preview's voice. */
  can: string;
};

export type PermissionGroup = { resource: string; permissions: Permission[]; total: number };

export const permissionCatalogue: PermissionGroup[] = [
  {
    resource: 'Learners',
    total: 8,
    permissions: [
      {
        code: 'completion:read',
        title: 'See who has completed what',
        detail:
          'Read completion records for learners in the groups this person administers.',
        can: 'See completion for the groups they administer.',
      },
      {
        code: 'assignment:write',
        title: 'Assign training to a person',
        detail: 'Add or remove an obligation for one learner.',
        can: 'Assign training to one person at a time.',
      },
      {
        code: 'assignment:bulk',
        title: 'Assign training to the whole company',
        // The count is the point. A permission whose blast radius is five thousand people should
        // say five thousand people at the moment somebody is deciding whether to grant it.
        detail: 'Create an obligation for every active learner at once — currently 5,182 people.',
        can: 'Assign anything company-wide.',
      },
      {
        code: 'user:deactivate',
        title: 'Deactivate a person',
        detail: 'Ends their access at the next request. Their records are kept.',
        can: 'Deactivate anyone.',
      },
    ],
  },
  {
    resource: 'Courses & content',
    total: 19,
    permissions: [
      { code: 'course:read', title: 'Read any course, draft or published', can: 'Read every course, draft or published.' },
      {
        code: 'course:publish',
        title: 'Publish a course version',
        detail: 'Makes the current draft the one learners are given from now on.',
        can: 'Publish a course.',
      },
    ],
  },
  {
    resource: 'Assessment & marking',
    total: 22,
    permissions: [
      {
        code: 'grading:write',
        title: 'Mark written answers',
        detail: 'Work the queue of attempts waiting on a person.',
        can: 'Mark written answers in the queue.',
      },
      {
        code: 'integrity:read',
        title: 'See what a learner’s browser reported during an exam',
        // Straight out of docs/integrity-signals.md. An administrator granting this should be told
        // what it is worth before they are told they can have it.
        detail:
          'Self-reported signals — corroboration, not evidence. Absence of signals means nothing.',
        can: 'See integrity signals.',
      },
    ],
  },
];

const granted = new Set([
  'completion:read',
  'assignment:write',
  'course:read',
  'grading:write',
]);

export function RoleEditor() {
  const [selected, setSelected] = useState<Set<string>>(() => new Set(granted));
  const [filter, setFilter] = useState('');

  const groups = useMemo(() => {
    const term = filter.trim().toLowerCase();
    if (!term) {
      return permissionCatalogue;
    }
    return permissionCatalogue
      .map((group) => ({
        ...group,
        permissions: group.permissions.filter((permission) =>
          `${permission.title} ${permission.code} ${permission.detail ?? ''}`
            .toLowerCase()
            .includes(term),
        ),
      }))
      .filter((group) => group.permissions.length > 0);
  }, [filter]);

  const all = permissionCatalogue.flatMap((group) => group.permissions);
  const can = all.filter((permission) => selected.has(permission.code));
  const cannot = all.filter((permission) => !selected.has(permission.code));

  function toggle(code: string) {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(code)) {
        next.delete(code);
      } else {
        next.add(code);
      }
      return next;
    });
  }

  return (
    <div className="role panel">
      <div className="role__head">
        <div>
          <span className="u-caps">Role</span>
          <h1 className="u-display role__name">Site Compliance Lead</h1>
          <p className="u-meta">
            {selected.size} of 96 permissions · held by 412 people · last changed 2 Sep by J. Okafor
          </p>
        </div>
        <div className="role__actions">
          <button type="button" className="btn btn-secondary">
            Clone
          </button>
          {/*
           * THE REACH IS ON THE BUTTON, not in a dialog after it. A save that silently changes what
           * 412 people can do is the destructive action this console is most likely to perform by
           * accident, and the count belongs where the decision is made.
           */}
          <button type="button" className="btn btn-primary">
            Save — affects 412 people
          </button>
        </div>
      </div>

      <div className="role__split">
        <div className="role__list">
          <div className="role__filter">
            <label className="u-caps" htmlFor="permission-filter">
              Filter permissions
            </label>
            <input
              id="permission-filter"
              className="input input-dense"
              placeholder="Filter permissions"
              value={filter}
              onChange={(event) => setFilter(event.target.value)}
            />
          </div>

          {groups.map((group) => (
            <fieldset key={group.resource} className="role__group">
              <legend className="role__legend">
                <span>{group.resource}</span>
                <span className="u-meta">
                  {group.permissions.filter((p) => selected.has(p.code)).length} of {group.total}{' '}
                  selected
                </span>
              </legend>
              <div className="panel">
                {group.permissions.map((permission) => (
                  <label key={permission.code} className="permission">
                    <input
                      type="checkbox"
                      checked={selected.has(permission.code)}
                      onChange={() => toggle(permission.code)}
                    />
                    <span>
                      <span className="permission__title">{permission.title}</span>
                      {permission.detail ? (
                        <span className="permission__detail">{permission.detail}</span>
                      ) : null}
                      <code className="permission__code">{permission.code}</code>
                    </span>
                  </label>
                ))}
              </div>
            </fieldset>
          ))}
        </div>

        {/*
         * The preview is a live region: it is the answer to "what did that just do", and somebody
         * using a screen reader ticks a box and otherwise hears nothing change.
         */}
        <aside className="role__preview" aria-labelledby="preview" aria-live="polite">
          <div className="role__preview-head">
            <h2 id="preview" className="u-caps">
              What this role can do
            </h2>
            <p className="u-meta">Written from the selection on the left, as it changes.</p>
          </div>
          <div className="role__preview-body">
            <section>
              <h3 className="u-caps">Can</h3>
              <ul>
                {can.map((permission) => (
                  <li key={permission.code}>{permission.can}</li>
                ))}
              </ul>
            </section>
            <section className="role__cannot">
              <h3 className="u-caps">Cannot</h3>
              <ul>
                {cannot.map((permission) => (
                  <li key={permission.code}>{permission.can}</li>
                ))}
              </ul>
            </section>
            {/*
             * ABSENT, NOT GREYED OUT. A group admin holding this role does not see a disabled view
             * of the rest of the company -- they see their own people, because the API answers 404
             * rather than saying whether the others exist (T-2.4). A screen that draws them dimmed
             * has leaked the thing the disclosure rule refuses to say.
             */}
            <p className="u-meta role__reach">
              Reach is still limited by their groups: a group admin holding this role sees their own
              people, and the rest of the company is <strong>absent</strong>, not greyed out.
            </p>
          </div>
        </aside>
      </div>
    </div>
  );
}
