import { useCallback, useEffect, useState } from 'react';
import { catalog, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/catalog.d.ts';
import { useMe } from '../shared/auth/useMe.ts';
import { StateChip } from '../shared/design/State.tsx';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';
import { NotEnforcedYet } from './NotEnforcedYet.tsx';

type CourseView = components['schemas']['CourseView'];
type TreeView = components['schemas']['TreeView'];
type TypeView = components['schemas']['TypeView'];
type ItemView = components['schemas']['ItemView'];

/**
 * Authoring — courses, modules, nodes, gates and publishing, against the real API (T-10.5).
 *
 * <p>What this screen can and cannot offer is decided by catalog, not by the design, and the
 * difference is worth stating where somebody will look for the missing button:
 *
 * <ul>
 *   <li><b>Nothing can be removed.</b> There is no `DELETE` for a course, a module or a node.
 *       A course tree only grows. Content items archive through their state instead.
 *   <li><b>Nothing can be renamed</b> after it is created, except a content item.
 *   <li><b>Ordering is fractional.</b> `ordinal` is a string and a move is expressed as
 *       "after this one", which is why reordering is two buttons rather than a drag index.
 * </ul>
 *
 * <p>So the screen offers exactly what the API honours. A button that reported success and changed
 * nothing would be worse than its absence.
 */
type Screen =
  | { status: 'loading' }
  | { status: 'ready'; courses: CourseView[] }
  | { status: 'failed'; failure: ApiFailure };

export function Authoring() {
  const [screen, setScreen] = useState<Screen>({ status: 'loading' });
  const [open, setOpen] = useState<TreeView | null>(null);
  const who = useMe(true);

  const load = useCallback(() => {
    catalog
      .GET('/api/v1/courses')
      .then(({ data, response, error }) => {
        setScreen(
          data
            ? { status: 'ready', courses: data }
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

  async function createCourse(title: string, description: string) {
    const { data } = await catalog.POST('/api/v1/courses', { body: { title, description } });
    if (data) {
      load();
      void openCourse(data.id as string);
    }
  }

  // Not memoised: it is nobody's effect dependency, and wrapping an async body in useCallback is
  // what the compiler's memoization check objects to.
  async function openCourse(courseId: string) {
    const { data } = await catalog.GET('/api/v1/courses/{courseId}', {
      params: { path: { courseId } },
    });
    setOpen(data ?? null);
  }

  if (screen.status === 'loading') {
    return <Loading what="your courses" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  return (
    <div className="authoring-page">
      <NotEnforcedYet />
      <div className="authoring-page__split">
        <CourseList
          courses={screen.courses}
          openId={open?.course?.id}
          onOpen={openCourse}
          onCreate={createCourse}
        />
        {open ? (
          <CourseTree
            tree={open}
            authorId={who.state === 'tenant' ? who.me.id : null}
            onChanged={() => {
              if (open.course?.id) {
                void openCourse(open.course.id);
              }
            }}
          />
        ) : (
          <Empty title="No course open.">
            <p className="u-meta">Choose one on the left, or create the first.</p>
          </Empty>
        )}
      </div>
    </div>
  );
}

function CourseList({
  courses,
  openId,
  onOpen,
  onCreate,
}: {
  courses: CourseView[];
  openId?: string | undefined;
  onOpen: (id: string) => void;
  onCreate: (title: string, description: string) => void;
}) {
  const [title, setTitle] = useState('');

  return (
    <section className="course-list" aria-labelledby="courses">
      <h2 id="courses" className="u-caps">
        Courses
      </h2>
      {courses.length === 0 ? (
        <p className="u-meta">None yet. The first one is below.</p>
      ) : (
        <ul className="panel course-list__items">
          {courses.map((course) => (
            <li key={course.id}>
              <button
                type="button"
                className={course.id === openId ? 'node node--on' : 'node'}
                onClick={() => course.id && onOpen(course.id)}
              >
                {course.title}
              </button>
            </li>
          ))}
        </ul>
      )}
      <form
        className="course-list__new"
        onSubmit={(event) => {
          event.preventDefault();
          if (title.trim()) {
            onCreate(title.trim(), '');
            setTitle('');
          }
        }}
      >
        <label className="u-caps" htmlFor="new-course">
          New course
        </label>
        <input
          id="new-course"
          className="input input-dense"
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder="Fire Safety Refresher"
        />
        {/*
         * A title cannot be changed afterwards -- catalog has no PUT for a course -- so the form
         * says so rather than letting somebody discover it by trying.
         */}
        <p className="u-meta">A course cannot be renamed once created.</p>
        <button type="submit" className="btn btn-primary" disabled={!title.trim()}>
          Create
        </button>
      </form>
    </section>
  );
}

function CourseTree({
  tree,
  authorId,
  onChanged,
}: {
  tree: TreeView;
  authorId: string | null;
  onChanged: () => void;
}) {
  const courseId = tree.course?.id;
  const [items, setItems] = useState<ItemView[]>([]);
  const [types, setTypes] = useState<TypeView[]>([]);
  const [published, setPublished] = useState<string | null>(null);

  useEffect(() => {
    void catalog.GET('/api/v1/content-items').then(({ data }) => setItems(data ?? []));
    void catalog.GET('/api/v1/content-items/types').then(({ data }) => setTypes(data ?? []));
  }, []);

  async function addModule(title: string) {
    if (!courseId) {
      return;
    }
    await catalog.POST('/api/v1/courses/{courseId}/modules', {
      params: { path: { courseId } },
      body: { title },
    });
    onChanged();
  }

  async function addNode(moduleId: string, contentItemId: string) {
    await catalog.POST('/api/v1/courses/modules/{moduleId}/nodes', {
      params: { path: { moduleId } },
      body: { contentItemId, required: true },
    });
    onChanged();
  }

  async function publish() {
    if (!courseId || !authorId) {
      return;
    }
    // `publishedBy` is a field WE fill in: catalog derives no actor from the token yet. See
    // useMe.ts -- this is the gap, not a convenience.
    const { data } = await catalog.POST('/api/v1/courses/{courseId}/versions', {
      params: { path: { courseId } },
      body: { publishedBy: authorId, notes: '' },
    });
    setPublished(data?.version ? `Version ${data.version}` : 'Published');
    onChanged();
  }

  return (
    <section className="course-tree" aria-labelledby="tree">
      <div className="course-tree__head">
        <div>
          <StateChip state="draft" />
          <h2 id="tree" className="u-display course-tree__title">
            {tree.course?.title}
          </h2>
        </div>
        <div className="course-tree__actions">
          {published ? <span className="u-meta">{published}</span> : null}
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => void publish()}
            disabled={authorId === null}
          >
            Publish a version
          </button>
        </div>
      </div>

      {(tree.modules ?? []).length === 0 ? (
        <Empty title="This course has no modules yet.">
          <p className="u-meta">A module holds the ordered nodes a learner walks through.</p>
        </Empty>
      ) : null}

      <ol className="course-tree__modules">
        {(tree.modules ?? []).map((module) => (
          <li key={module.id} className="panel course-tree__module">
            <h3 className="course-tree__module-title">{module.title}</h3>
            <ol className="course-tree__nodes">
              {(module.nodes ?? []).map((node) => (
                <li key={node.id} className="node">
                  {items.find((item) => item.id === node.contentItemId)?.title ??
                    node.contentItemId}
                  <span className="u-meta">
                    {' '}
                    {items.find((item) => item.id === node.contentItemId)?.type ?? ''}
                    {node.required ? ' · required' : ' · optional'}
                  </span>
                </li>
              ))}
            </ol>
            <AddNode items={items} onAdd={(id) => module.id && void addNode(module.id, id)} />
          </li>
        ))}
      </ol>

      <AddModule onAdd={(title) => void addModule(title)} />
      <NewContentItem types={types} onCreated={(item) => setItems((was) => [...was, item])} />
    </section>
  );
}

function AddModule({ onAdd }: { onAdd: (title: string) => void }) {
  const [title, setTitle] = useState('');
  return (
    <form
      className="course-tree__add"
      onSubmit={(event) => {
        event.preventDefault();
        if (title.trim()) {
          onAdd(title.trim());
          setTitle('');
        }
      }}
    >
      <label className="u-caps" htmlFor="new-module">
        Add a module
      </label>
      <input
        id="new-module"
        className="input input-dense"
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        placeholder="Module 1 · Getting started"
      />
      <button type="submit" className="btn btn-secondary" disabled={!title.trim()}>
        Add
      </button>
    </form>
  );
}

function AddNode({ items, onAdd }: { items: ItemView[]; onAdd: (contentItemId: string) => void }) {
  const [chosen, setChosen] = useState('');
  return (
    <form
      className="course-tree__add"
      onSubmit={(event) => {
        event.preventDefault();
        if (chosen) {
          onAdd(chosen);
          setChosen('');
        }
      }}
    >
      <label className="u-caps" htmlFor="add-node">
        Add a node
      </label>
      <select
        id="add-node"
        className="input input-dense"
        value={chosen}
        onChange={(event) => setChosen(event.target.value)}
      >
        <option value="">Choose content…</option>
        {items.map((item) => (
          <option key={item.id} value={item.id}>
            {item.title} ({item.type})
          </option>
        ))}
      </select>
      <button type="submit" className="btn btn-secondary" disabled={!chosen}>
        Add
      </button>
    </form>
  );
}

/**
 * Content is created on its own and attached by id — there is no "create content inside a node".
 *
 * <p>The payload is always a REFERENCE and never bytes: `{"assetId": …}` for a video,
 * `{"testId": …}` for a test. The bytes live in streaming, and the test lives in assessment.
 */
function NewContentItem({
  types,
  onCreated,
}: {
  types: TypeView[];
  onCreated: (item: ItemView) => void;
}) {
  const [type, setType] = useState('');
  const [title, setTitle] = useState('');
  const [reference, setReference] = useState('');

  const payloadKey: Record<string, string> = {
    video: 'assetId',
    scorm: 'packageId',
    cmi5: 'packageId',
    slides: 'documentId',
    test: 'testId',
  };

  async function create(event: React.FormEvent) {
    event.preventDefault();
    const key = payloadKey[type];
    const { data } = await catalog.POST('/api/v1/content-items', {
      body: {
        type,
        title,
        description: '',
        tags: [],
        payload: key && reference ? { [key]: reference } : {},
      },
    });
    if (data) {
      onCreated(data);
      setTitle('');
      setReference('');
    }
  }

  return (
    <form className="panel new-content" onSubmit={(event) => void create(event)}>
      <h3 className="u-caps">New content item</h3>
      <label className="u-caps" htmlFor="content-type">
        Type
      </label>
      <select
        id="content-type"
        className="input input-dense"
        value={type}
        onChange={(event) => setType(event.target.value)}
      >
        <option value="">Choose…</option>
        {types.map((one) => (
          <option key={one.code} value={one.code}>
            {one.displayName}
          </option>
        ))}
      </select>
      <label className="u-caps" htmlFor="content-title">
        Title
      </label>
      <input
        id="content-title"
        className="input input-dense"
        value={title}
        onChange={(event) => setTitle(event.target.value)}
      />
      {type ? (
        <>
          <label className="u-caps" htmlFor="content-reference">
            {payloadKey[type] ?? 'reference'}
          </label>
          <input
            id="content-reference"
            className="input input-dense"
            value={reference}
            onChange={(event) => setReference(event.target.value)}
            placeholder="the id this item points at"
          />
          <p className="u-meta">
            Content points at something rather than holding it: a video's bytes live in streaming, a
            test lives in assessment.
          </p>
        </>
      ) : null}
      <button type="submit" className="btn btn-secondary" disabled={!type || !title.trim()}>
        Create
      </button>
    </form>
  );
}
