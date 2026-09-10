/**
 * What the console does not protect, said on the console (ADR-0109, T-9.11).
 *
 * <p>Catalog and assessment carry no `@PreAuthorize` on anything. That is a recorded decision
 * rather than an oversight — the permission grants live in `identity` and nothing can evaluate
 * them from those processes until ADR-0109's merge — but the consequence is that every authoring
 * call succeeds for anyone signed in to the company.
 *
 * <p>It is written on the screen because the alternative is a UI that looks like it has
 * administrators. A control that appears to be restricted, and is not, is worse than an open one:
 * somebody plans around it.
 */
import { useT } from '../shared/i18n/useLocale.ts';

export function NotEnforcedYet() {
  const t = useT();
  return (
    <p className="not-enforced" role="note">
      <span className="u-caps">{t('not-enforced.label')}</span>
      <span>{t('not-enforced.body')}</span>
    </p>
  );
}
