import { useCallback, useEffect, useState } from 'react';
import { catalog, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import { buttonClasses } from '../shared/design/Button.tsx';
import { fieldClasses } from '../shared/design/Field.tsx';
import type { components } from '../shared/api/catalog.d.ts';
import { useMe } from '../shared/auth/useMe.ts';
import { StateChip } from '../shared/design/State.tsx';
import { useT } from '../shared/i18n/useLocale.ts';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';
import { NotEnforcedYet } from './NotEnforcedYet.tsx';
import { UploadPanel } from './Upload.tsx';

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
  const t = useT();
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
    return <Loading what="loading.courses" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  return (
    <div className="flex flex-col gap-6">
      <NotEnforcedYet />
      <div className="grid gap-6 desk:grid-cols-[20rem_1fr] desk:items-start">
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
          <Empty title={t('authoring.no-course.title')}>
            <p className="text-sm text-muted">{t('authoring.no-course.body')}</p>
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
  const t = useT();
  const [title, setTitle] = useState('');

  return (
    <section className="flex flex-col gap-3" aria-labelledby="courses">
      <h2 id="courses" className="label-caps">
        {t('authoring.courses')}
      </h2>
      {courses.length === 0 ? (
        <p className="text-sm text-muted">{t('authoring.none-yet')}</p>
      ) : (
        <ul className="flex flex-col overflow-hidden rounded-xl border border-hairline bg-surface">
          {courses.map((course) => (
            <li key={course.id}>
              <button
                type="button"
                className={`flex min-h-9 w-full items-center gap-2 border-b border-hairline px-4 text-start text-sm transition-colors duration-150 last:border-b-0 ${course.id === openId ? 'bg-brand-tint font-semibold text-brand' : 'text-muted hover:bg-surface-muted hover:text-ink'}`}
                onClick={() => course.id && onOpen(course.id)}
              >
                {course.title}
              </button>
            </li>
          ))}
        </ul>
      )}
      <form
        className="flex flex-col gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          if (title.trim()) {
            onCreate(title.trim(), '');
            setTitle('');
          }
        }}
      >
        <label className="label-caps" htmlFor="new-course">
          {t('authoring.new-course')}
        </label>
        <input
          id="new-course"
          className={fieldClasses(true)}
          value={title}
          onChange={(event) => setTitle(event.target.value)}
          placeholder={t('authoring.new-course.placeholder')}
        />
        {/*
         * A title cannot be changed afterwards -- catalog has no PUT for a course -- so the form
         * says so rather than letting somebody discover it by trying.
         */}
        <p className="text-sm text-muted">{t('authoring.no-rename')}</p>
        <button type="submit" className={buttonClasses('primary', 'sm')} disabled={!title.trim()}>
          {t('authoring.create')}
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
  const t = useT();
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
    setPublished(
      data?.version
        ? t('authoring.version', { version: data.version })
        : t('authoring.published'),
    );
    onChanged();
  }

  return (
    <section className="flex flex-col gap-4" aria-labelledby="tree">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div>
          <StateChip state="draft" />
          <h2 id="tree" className="font-display text-lg font-bold">
            {tree.course?.title}
          </h2>
        </div>
        <div className="flex flex-wrap gap-2">
          {published ? <span className="text-sm text-muted">{published}</span> : null}
          <button
            type="button"
            className={buttonClasses('primary', 'sm')}
            onClick={() => void publish()}
            disabled={authorId === null}
          >
            {t('authoring.publish-version')}
          </button>
        </div>
      </div>

      {(tree.modules ?? []).length === 0 ? (
        <Empty title={t('authoring.no-modules.title')}>
          <p className="text-sm text-muted">{t('authoring.no-modules.body')}</p>
        </Empty>
      ) : null}

      <ol className="flex flex-col gap-3">
        {(tree.modules ?? []).map((module) => (
          <li key={module.id} className="flex flex-col gap-2 rounded-xl border border-hairline bg-surface p-4">
            <h3 className="flex flex-wrap items-center gap-2 font-semibold">{module.title}</h3>
            <ol className="flex flex-col">
              {(module.nodes ?? []).map((node) => (
                <li key={node.id} className="flex min-h-9 w-full items-center gap-2 border-b border-hairline px-4 text-start text-sm transition-colors duration-150 last:border-b-0 text-muted hover:bg-surface-muted hover:text-ink">
                  {items.find((item) => item.id === node.contentItemId)?.title ??
                    node.contentItemId}
                  <span className="text-sm text-muted">
                    {' '}
                    {items.find((item) => item.id === node.contentItemId)?.type ?? ''}
                    {` · ${t(node.required ? 'authoring.required' : 'authoring.optional')}`}
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
  const t = useT();
  const [title, setTitle] = useState('');
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        if (title.trim()) {
          onAdd(title.trim());
          setTitle('');
        }
      }}
    >
      <label className="label-caps" htmlFor="new-module">
        {t('authoring.add-module')}
      </label>
      <input
        id="new-module"
        className={fieldClasses(true)}
        value={title}
        onChange={(event) => setTitle(event.target.value)}
        placeholder={t('authoring.add-module.placeholder')}
      />
      <button type="submit" className={buttonClasses('secondary', 'sm')} disabled={!title.trim()}>
        {t('authoring.add')}
      </button>
    </form>
  );
}

function AddNode({ items, onAdd }: { items: ItemView[]; onAdd: (contentItemId: string) => void }) {
  const t = useT();
  const [chosen, setChosen] = useState('');
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        if (chosen) {
          onAdd(chosen);
          setChosen('');
        }
      }}
    >
      <label className="label-caps" htmlFor="add-node">
        {t('authoring.add-node')}
      </label>
      <select
        id="add-node"
        className={fieldClasses(true)}
        value={chosen}
        onChange={(event) => setChosen(event.target.value)}
      >
        <option value="">{t('authoring.choose-content')}</option>
        {items.map((item) => (
          <option key={item.id} value={item.id}>
            {item.title} ({item.type})
          </option>
        ))}
      </select>
      <button type="submit" className={buttonClasses('secondary', 'sm')} disabled={!chosen}>
        {t('authoring.add')}
      </button>
    </form>
  );
}

/**
 * Content is created on its own and attached by id — there is no "create content inside a node".
 *
 * <p>The payload is always a REFERENCE and never bytes: `{"assetId": …}` for a video,
 * `{"packageId": …}` for SCORM, `{"testId": …}` for a test. The bytes live in streaming and in
 * packaging; the test lives in assessment. Catalog stores the id and asks the owner when it needs
 * anything else, which is the data-ownership rule (ADR-0109) at the one table most likely to break
 * it.
 *
 * <h2>The reference field is still typeable, and that is not laziness</h2>
 *
 * <p>{@link UploadPanel} fills it in for the two kinds this product can now upload. It is left
 * editable because the id is the contract: an author re-attaching a video that was uploaded last
 * month, or pointing at a test somebody built in the assessment screens, has an id and no file. A
 * field that only a fresh upload could fill would make the second case impossible in the product,
 * which is the state this screen was in before the panel existed.
 *
 * <p><b>`test` deliberately has no upload panel</b> — a test is built in the assessment screens,
 * not uploaded — and neither does anything else the registry might grow. A panel appears for a
 * type only when there is a real upload behind it.
 */
function NewContentItem({
  types,
  onCreated,
}: {
  types: TypeView[];
  onCreated: (item: ItemView) => void;
}) {
  const t = useT();
  const [type, setType] = useState('');
  const [title, setTitle] = useState('');
  const [reference, setReference] = useState('');

  const payloadKey: Record<string, string> = {
    video: 'assetId',
    scorm: 'packageId',
    cmi5: 'packageId',
    html5: 'packageId',
    slides: 'documentId',
    test: 'testId',
  };

  /**
   * The types that can be uploaded from here, and the ones that cannot.
   *
   * <p>A closed lookup rather than a check for "is it not a test", so that adding a sixth content
   * type has to say explicitly whether a file can be sent for it — the default is no panel, which
   * is the honest default for a type whose bytes nothing yet accepts.
   */
  const uploadable: Record<string, 'video' | 'scorm' | 'cmi5' | 'html5' | 'slides'> = {
    video: 'video',
    scorm: 'scorm',
    cmi5: 'cmi5',
    html5: 'html5',
    slides: 'slides',
  };
  const canUpload = uploadable[type];

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
    <form className="card flex flex-col gap-3 p-5" onSubmit={(event) => void create(event)}>
      <h3 className="label-caps">{t('authoring.new-item')}</h3>
      <label className="label-caps" htmlFor="content-type">
        {t('authoring.type')}
      </label>
      <select
        id="content-type"
        className={fieldClasses(true)}
        value={type}
        onChange={(event) => setType(event.target.value)}
      >
        <option value="">{t('authoring.choose')}</option>
        {types.map((one) => (
          <option key={one.code} value={one.code}>
            {one.displayName}
          </option>
        ))}
      </select>
      <label className="label-caps" htmlFor="content-title">
        {t('authoring.title')}
      </label>
      <input
        id="content-title"
        className={fieldClasses(true)}
        value={title}
        onChange={(event) => setTitle(event.target.value)}
      />
      {canUpload ? (
        <UploadPanel
          kind={canUpload}
          onUploaded={(id, suggested) => {
            setReference(id);
            /*
             * The package's own title is a SUGGESTION and only fills an empty field.
             *
             * Authoring tools call things "Untitled Course 3", and a customer's catalogue should
             * not inherit that -- but neither should an author who typed a title watch it be
             * overwritten by the file they just attached.
             */
            if (suggested) {
              setTitle((was) => (was.trim() ? was : suggested));
            }
          }}
        />
      ) : null}
      {type ? (
        <>
          <label className="label-caps" htmlFor="content-reference">
            {/*
             * A payload KEY, not a word: `assetId`, `testId`. It names the field the API expects,
             * so it stays as it is written in the contract and only the fallback is translated.
             */}
            {payloadKey[type] ?? t('authoring.reference')}
          </label>
          <input
            id="content-reference"
            className={fieldClasses(true)}
            value={reference}
            onChange={(event) => setReference(event.target.value)}
            placeholder={t('authoring.reference.placeholder')}
          />
          <p className="text-sm text-muted">{t('authoring.points-at')}</p>
        </>
      ) : null}
      <button type="submit" className={buttonClasses('secondary', 'sm')} disabled={!type || !title.trim()}>
        {t('authoring.create')}
      </button>
    </form>
  );
}
