import { useCallback, useEffect, useState } from 'react';
import { catalog, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/catalog.d.ts';
import { useMe } from '../shared/auth/useMe.ts';
import { StateChip } from '../shared/design/State.tsx';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';
import { NotEnforcedYet } from './NotEnforcedYet.tsx';

type CourseView = components['schemas']['CourseView'];
type AssignmentView = components['schemas']['AssignmentView'];

/**
 * Assigning training, and revoking it (T-10.4, T-5.5).
 *
 * <p>Three targets, and the third is the one to be careful with: `USER`, `GROUP` — which reaches
 * every descendant group — and `TENANT`, the whole company, which carries no target id at all.
 * The design's rule is kept: <b>what this affects is shown before it is confirmed</b>, and for the
 * company-wide case that is a sentence rather than a number, because catalog exposes no count and
 * a made-up one would be worse than none.
 *
 * <p><b>An assignment cannot be edited.</b> There is no `PUT`; changing a due date means revoking
 * and assigning again. The screen says so where somebody would look for the edit button.
 */
type Screen =
  | { status: 'loading' }
  | { status: 'ready'; courses: CourseView[]; assignments: AssignmentView[] }
  | { status: 'failed'; failure: ApiFailure };

export function Assign() {
  const [screen, setScreen] = useState<Screen>({ status: 'loading' });
  const who = useMe(true);

  const load = useCallback(() => {
    Promise.all([catalog.GET('/api/v1/courses'), catalog.GET('/api/v1/assignments')])
      .then(([courses, assignments]) => {
        if (courses.data && assignments.data) {
          setScreen({
            status: 'ready',
            courses: courses.data,
            assignments: assignments.data,
          });
        } else {
          setScreen({
            status: 'failed',
            failure: failureFrom(courses.response, courses.error ?? assignments.error),
          });
        }
      })
      .catch((unreachable: unknown) => {
        setScreen({ status: 'failed', failure: failureFrom(undefined, unreachable) });
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (screen.status === 'loading') {
    return <Loading what="assignments" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  return (
    <div className="assign">
      <NotEnforcedYet />
      <AssignForm
        courses={screen.courses}
        assignedBy={who.state === 'tenant' ? who.me.id : null}
        onAssigned={load}
      />
      <Existing assignments={screen.assignments} courses={screen.courses} onRevoked={load} />
    </div>
  );
}

function AssignForm({
  courses,
  assignedBy,
  onAssigned,
}: {
  courses: CourseView[];
  assignedBy: string | null;
  onAssigned: () => void;
}) {
  const [courseId, setCourseId] = useState('');
  const [targetType, setTargetType] = useState<'USER' | 'GROUP' | 'TENANT'>('USER');
  const [targetId, setTargetId] = useState('');
  const [dueOn, setDueOn] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const ready = courseId !== '' && (targetType === 'TENANT' || targetId.trim() !== '');

  const reach =
    targetType === 'TENANT'
      ? 'every active learner in this company'
      : targetType === 'GROUP'
        ? 'everyone in that group, including every group beneath it'
        : 'one person';

  async function assign() {
    if (!assignedBy) {
      setProblem('Your account has no identity in this company, so nothing can be assigned as you.');
      return;
    }
    const { response, error } = await catalog.POST('/api/v1/assignments', {
      body: {
        referenceType: 'COURSE',
        referenceId: courseId,
        targetType,
        // TENANT carries no target id -- the API refuses one.
        ...(targetType === 'TENANT' ? {} : { targetId: targetId.trim() }),
        assignedBy,
        reminderOffsets: [],
        due: dueOn ? { kind: 'ABSOLUTE', on: dueOn } : { kind: 'NONE' },
      },
    });
    if (error || !response?.ok) {
      setProblem(failureFrom(response, error).message);
      return;
    }
    setConfirming(false);
    setProblem(null);
    setTargetId('');
    onAssigned();
  }

  return (
    <section className="assign__form panel" aria-labelledby="assign-heading">
      <h2 id="assign-heading" className="u-caps">
        Assign a course
      </h2>

      <label className="u-caps" htmlFor="assign-course">
        Course
      </label>
      <select
        id="assign-course"
        className="input input-dense"
        value={courseId}
        onChange={(event) => setCourseId(event.target.value)}
      >
        <option value="">Choose…</option>
        {courses.map((course) => (
          <option key={course.id} value={course.id}>
            {course.title}
          </option>
        ))}
      </select>

      <label className="u-caps" htmlFor="assign-target">
        To
      </label>
      <select
        id="assign-target"
        className="input input-dense"
        value={targetType}
        onChange={(event) => setTargetType(event.target.value as 'USER' | 'GROUP' | 'TENANT')}
      >
        <option value="USER">One person</option>
        <option value="GROUP">A group</option>
        <option value="TENANT">The whole company</option>
      </select>

      {targetType === 'TENANT' ? null : (
        <>
          <label className="u-caps" htmlFor="assign-target-id">
            {targetType === 'USER' ? 'Learner id' : 'Group id'}
          </label>
          <input
            id="assign-target-id"
            className="input input-dense"
            value={targetId}
            onChange={(event) => setTargetId(event.target.value)}
            placeholder="uuid"
          />
          {/*
           * An id rather than a picker, and not by choice: identity publishes no user-list
           * endpoint and no group-member list, so there is nothing to populate a picker from.
           */}
          <p className="u-meta">
            An id, because identity has no endpoint that lists people or group members yet.
          </p>
        </>
      )}

      <label className="u-caps" htmlFor="assign-due">
        Due on
      </label>
      <input
        id="assign-due"
        type="date"
        className="input input-dense"
        value={dueOn}
        onChange={(event) => setDueOn(event.target.value)}
      />
      <p className="u-meta">
        A due date cannot be changed afterwards — catalog has no update for an assignment. Changing
        it means revoking this one and assigning again.
      </p>

      {problem ? (
        <p className="authoring__short" role="alert">
          {problem}
        </p>
      ) : null}

      {/*
       * THE REACH BEFORE THE CONFIRM. The design's rule, and the one that matters most on this
       * screen: assigning to a company is thousands of obligations and there is no undo beyond
       * revoking each one.
       */}
      {confirming ? (
        <div className="assign__confirm" role="alert">
          <p>
            This assigns <strong>{courses.find((c) => c.id === courseId)?.title}</strong> to{' '}
            <strong>{reach}</strong>.
          </p>
          <div className="assign__confirm-actions">
            <button type="button" className="btn btn-primary" onClick={() => void assign()}>
              Yes, assign it
            </button>
            <button type="button" className="btn btn-secondary" onClick={() => setConfirming(false)}>
              Cancel
            </button>
          </div>
        </div>
      ) : (
        <button
          type="button"
          className="btn btn-primary"
          disabled={!ready}
          onClick={() => setConfirming(true)}
        >
          Assign…
        </button>
      )}
    </section>
  );
}

function Existing({
  assignments,
  courses,
  onRevoked,
}: {
  assignments: AssignmentView[];
  courses: CourseView[];
  onRevoked: () => void;
}) {
  async function revoke(id: string) {
    await catalog.DELETE('/api/v1/assignments/{assignmentId}', {
      params: { path: { assignmentId: id } },
    });
    onRevoked();
  }

  if (assignments.length === 0) {
    return (
      <Empty title="Nothing is assigned in this company.">
        <p className="u-meta">Until something is, every learner’s home screen is empty.</p>
      </Empty>
    );
  }

  return (
    <section className="assign__existing" aria-labelledby="existing">
      <h2 id="existing" className="u-caps">
        Assigned
      </h2>
      <div className="u-scroll-x">
        <table className="table">
          <thead>
            <tr>
              <th scope="col">Course</th>
              <th scope="col">To</th>
              <th scope="col">Pinned version</th>
              <th scope="col" />
            </tr>
          </thead>
          <tbody>
            {assignments.map((assignment) => (
              <tr key={assignment.id}>
                <td>
                  {courses.find((course) => course.id === assignment.referenceId)?.title ??
                    assignment.referenceId}
                </td>
                <td>
                  {assignment.targetType === 'TENANT'
                    ? 'the whole company'
                    : `${assignment.targetType?.toLowerCase()} ${assignment.targetId}`}
                </td>
                <td>
                  {assignment.pinnedVersion ?? '—'}
                  {/*
                   * `drifted` means the course has been published since, and this obligation is
                   * still pinned to the older version. Worth showing: it is the difference between
                   * a learner seeing today's course and last month's.
                   */}
                  {assignment.drifted ? <StateChip state="draft" detail="behind" /> : null}
                </td>
                <td>
                  <button
                    type="button"
                    className="btn btn-secondary btn-dense"
                    onClick={() => assignment.id && void revoke(assignment.id)}
                  >
                    Revoke
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}
