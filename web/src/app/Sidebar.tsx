/**
 * The navigation panel: a side panel on a desktop, the contents of the drawer on a phone.
 *
 * <p>ONE PANEL, TWO TREES, AND ONE NAV ELEMENT IN THE ACCESSIBILITY TREE. The learner has three
 * destinations and an administrator has six, in the same place with the same shape. The shell
 * renders this twice — once inside a `hidden desk:flex` aside and once inside the drawer — and
 * exactly one of those is ever visible, which is what keeps a screen reader from meeting two
 * identical navigations. (In jsdom, where the suite runs and there is no CSS, the aside is the one
 * that is found; that is deliberate and is what `Shell.test.tsx` asserts against.)
 *
 * <p>WHY NOT A BOTTOM TAB BAR FOR THE LEARNER. It was one, and the argument for it was thumb
 * reach. What it cost was a product where "where am I" had two different answers depending which
 * half you were in, and a console whose six sections could not fit the pattern at all. The drawer
 * keeps the thumb argument honest instead: on a phone, navigation is one tap away and takes none
 * of the screen until it is asked for.
 */
import {
  BookOpen,
  ChartColumn,
  ClipboardCheck,
  Compass,
  FileCheck,
  House,
  Shield,
  Users,
  type LucideIcon,
} from 'lucide-react';
import { NavLink } from 'react-router';
import type { MessageKey } from '../shared/i18n/messages.en.ts';
import { useT } from '../shared/i18n/useLocale.ts';

type Destination = {
  to: string;
  label: MessageKey;
  icon: LucideIcon;
  /** `end` so "/" is not marked current on every route beneath it. */
  end?: boolean;
};

/*
 * Three destinations, and no more. A person who opens this a few times a year under obligation is
 * not served by a menu, and the console's six sections are not theirs to carry.
 */
const learner: Destination[] = [
  { to: '/', label: 'shell.tab.training', icon: House, end: true },
  { to: '/discover', label: 'shell.tab.discover', icon: Compass },
  { to: '/progress', label: 'shell.tab.progress', icon: ChartColumn },
];

const console_: Destination[] = [
  { to: '/admin/authoring', label: 'shell.nav.authoring', icon: BookOpen },
  { to: '/admin/assign', label: 'shell.nav.assign', icon: ClipboardCheck },
  { to: '/admin/grading', label: 'shell.nav.grading', icon: FileCheck },
  { to: '/admin/people', label: 'shell.nav.people', icon: Users },
  { to: '/admin/roles', label: 'shell.nav.roles', icon: Shield },
  { to: '/admin/compliance', label: 'shell.nav.compliance', icon: ChartColumn },
];

export function Sidebar({ inTheConsole }: { inTheConsole: boolean }) {
  const t = useT();
  const destinations = inTheConsole ? console_ : learner;

  return (
    <nav aria-label={t('shell.nav')} className="flex flex-col gap-1 p-3">
      {destinations.map(({ to, label, icon: Icon, end }) => (
        <NavLink
          key={to}
          to={to}
          end={end ?? false}
          className={({ isActive }) =>
            [
              'flex min-h-tap items-center gap-3 rounded-lg px-3 text-sm font-semibold transition-colors duration-150',
              isActive
                ? // The tint plus the accent text, and `aria-current` below carries the same fact
                  // to somebody who cannot see either.
                  'bg-brand-tint text-brand'
                : 'text-muted hover:bg-surface-muted hover:text-ink',
            ].join(' ')
          }
        >
          {/* `aria-hidden` on every icon in this file: the label beside it already says the word,
              and an icon announced as well means hearing every destination twice. */}
          <Icon aria-hidden="true" className="size-4.5 shrink-0" strokeWidth={2} />
          {t(label)}
        </NavLink>
      ))}
    </nav>
  );
}
