import { UploadCloud } from 'lucide-react';
import { useId, useState } from 'react';
import { buttonClasses } from '../shared/design/Button.tsx';
import { formatPercent } from '../shared/i18n/format.ts';
import { useLocale, useT } from '../shared/i18n/useLocale.ts';
import { uploadPackage, uploadVideo, type Stage } from './upload.ts';

/**
 * The panel that actually gets a file into the platform (T-3.2, T-4.1).
 *
 * <h2>Why this exists at all, stated where somebody will look for it</h2>
 *
 * <p>Until now the authoring screen could only reference content by an id somebody had obtained
 * elsewhere — there was a field labelled `assetId` and no way in the product to produce one. The
 * screen was honest about the API and useless as a way to publish a course, which is the shape a
 * feature takes when its two halves are built in different weeks.
 *
 * <h2>The two kinds of upload are different all the way down, and the panel says so</h2>
 *
 * <p>A video goes to the delivery provider's own target and is encoded on their machines; nothing
 * of ours ever holds the bytes (ADR-0101). A package goes to our object storage, is opened,
 * checked against ADR-0105's list, and served from a different origin than this application. Two
 * flows, two sets of sentences, one component only because the CONTROL is the same — choose a
 * file, watch it go, get an id.
 */
export function UploadPanel({
  kind,
  onUploaded,
}: {
  /** `video` for a streaming asset, or one of the package kinds catalog names. */
  kind: 'video' | 'scorm' | 'cmi5' | 'html5' | 'slides';
  /** Called with the id the content item's payload should reference. */
  onUploaded: (id: string, title: string | null) => void;
}) {
  const t = useT();
  const { locale } = useLocale();
  const inputId = useId();
  const [file, setFile] = useState<File | null>(null);
  const [stage, setStage] = useState<Stage>({ at: 'idle' });
  const busy = stage.at === 'creating' || stage.at === 'sending' || stage.at === 'processing';

  async function start() {
    if (!file) {
      return;
    }
    const result =
      kind === 'video'
        ? // The declared ceiling, not a measurement: the browser cannot know a video's duration
          // without decoding it, and the number is only ever an upper bound the provider enforces.
          // Four hours is longer than any training video and short enough to stop an accident.
          await uploadVideo(file, 4 * 60 * 60, setStage)
        : await uploadPackage(kind, file, setStage);
    if (result.at === 'done') {
      onUploaded(result.id, result.title ?? null);
    }
  }

  return (
    <div className="flex flex-col gap-3 rounded-xl border border-hairline bg-surface-muted p-4">
      <h4 className="label-caps">
        {t(kind === 'video' ? 'upload.video.heading' : 'upload.package.heading')}
      </h4>
      <p className="text-sm text-muted">
        {t(kind === 'video' ? 'upload.video.body' : 'upload.package.body')}
      </p>

      <div className="flex flex-wrap items-center gap-2">
        {/*
         * A LABEL STYLED AS A BUTTON, over a visually-hidden file input.
         *
         * `<input type="file">` cannot be styled to match anything, and the usual fix -- a real
         * button that calls `input.click()` -- breaks keyboard operation in Safari and hides the
         * chosen filename from assistive technology. A label pointing at the input keeps the
         * browser's own behaviour: it is focusable, Space and Enter open the picker, and the name
         * beside it is the input's value rather than a copy of it.
         */}
        <label htmlFor={inputId} className={`${buttonClasses('secondary', 'sm')} cursor-pointer`}>
          <UploadCloud aria-hidden="true" className="size-4" />
          {t('upload.choose-file')}
        </label>
        <input
          id={inputId}
          type="file"
          // The archive kinds are all ZIPs; video is left open, because the provider accepts far
          // more container formats than a list here would keep up with, and an `accept` that is
          // out of date silently hides a file an author is holding.
          accept={kind === 'video' ? 'video/*' : '.zip,application/zip'}
          className="sr-only"
          disabled={busy}
          onChange={(event) => {
            setFile(event.target.files?.[0] ?? null);
            setStage({ at: 'idle' });
          }}
        />
        <span className="min-w-0 truncate text-sm text-muted">
          {file ? file.name : t('upload.no-file')}
        </span>
        <button
          type="button"
          className={buttonClasses('primary', 'sm')}
          disabled={!file || busy}
          onClick={() => void start()}
        >
          {t('upload.start')}
        </button>
      </div>

      {/*
       * `aria-live="polite"` and not `assertive`: an upload's progress is not an interruption, and
       * a percentage announced assertively cuts across whatever somebody is reading, repeatedly.
       * The region exists in the DOM whether or not there is anything in it, because a live region
       * added at the same moment as its content is a live region browsers do not announce.
       */}
      <p aria-live="polite" className="min-h-5 text-sm text-muted">
        {stage.at === 'creating' ? t('upload.creating') : null}
        {stage.at === 'sending'
          ? t('upload.sending', {
              // `formatPercent` takes 0-100 the way the APIs report it, and puts the sign
              // on the side the language wants -- "62%" in English, "%62" in Turkish.
              percent: formatPercent(
                locale,
                stage.total > 0 ? (stage.sent / stage.total) * 100 : 0,
              ),
            })
          : null}
        {stage.at === 'processing'
          ? t(kind === 'video' ? 'upload.encoding' : 'upload.processing')
          : null}
        {stage.at === 'done' ? t('upload.ready') : null}
      </p>

      {stage.at === 'refused' ? (
        /*
         * The service's own sentence, verbatim -- it names the entry that broke the rule, and an
         * author with four hundred files needs exactly that. Drawn in the `failed` state's colours
         * because it IS a failure of their file; the distinction from the panel below is whose
         * problem it is to fix.
         */
        <p className="rounded-lg border border-failed-edge bg-failed-bg px-3 py-2 text-sm text-failed-fg">
          {t('upload.rejected', { reason: stage.reason })}
        </p>
      ) : null}
      {stage.at === 'failed' ? (
        <p className="rounded-lg border border-overdue-edge bg-overdue-bg px-3 py-2 text-sm text-overdue-fg">
          {stage.reason
            ? t('upload.failed', { reason: stage.reason })
            : t('upload.failed.unknown')}
        </p>
      ) : null}
    </div>
  );
}
