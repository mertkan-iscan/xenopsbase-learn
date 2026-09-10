import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

/**
 * The build fails on a lint error, so every rule here is a rule somebody has to obey rather than
 * a suggestion in an editor.
 *
 * The rules that are not standard are the last three, and they are all boundaries the build
 * enforces rather than conventions somebody remembers.
 *
 * The learner routes and the admin routes may not import each other: they are one deployable on
 * purpose (docs/frontend.md), and this is what keeps that from quietly becoming one bundle.
 *
 * And `src/player/**` may import neither. It is a third area rather than a corner of `shared/`
 * because T-10.7 publishes it as a package a customer embeds — at which point an import reaching
 * back into a screen is not a tidiness problem, it is the extraction failing. Cheaper to hold
 * now, when there is nothing to untangle.
 *
 * The fourth is `noEnglishInComponents`, and it is the same idea applied to words: a product with
 * two languages stays translated only if leaving a sentence behind is a build failure rather than
 * something somebody notices in Turkish six screens later.
 */

/**
 * No sentences typed into a component (docs/design-prompt.md, "constraints that are already
 * decided").
 *
 * <p>Two shapes catch nearly all of it. Text between tags is the obvious one. The other is the
 * handful of attributes that are read aloud or shown on hover — `aria-label`, `title`, `alt`,
 * `placeholder` — which are the ones that get left in English longest, because nobody sees them in
 * a screenshot.
 *
 * <p><b>Three letters, not one.</b> `{'·'}`, `&nbsp;` and a lone `%` are punctuation between two
 * translated values, not sentences, and a rule that flagged them would be turned off within a
 * week.
 *
 * <p><b>`src/player/**` is exempt on purpose.</b> It ships as its own package to customers who
 * embed it (T-10.7, ADR-0110), and it is told its language by the host page rather than by us — so
 * its two sentences go through the catalogue by choice, and a rule enforcing our i18n on a
 * separately published artifact would be enforcing the wrong boundary.
 */
const noEnglishInComponents = {
  'no-restricted-syntax': ['error',
    {
      selector: 'JSXText[value=/[A-Za-z]{3,}/]',
      message:
        'User-facing text belongs in src/shared/i18n/messages.en.ts, with its Turkish beside ' +
        'it. Use {t("some.key")}. A sentence typed here is a sentence that ships untranslated.',
    },
    {
      selector:
        'JSXAttribute[name.name=/^(aria-label|alt|title|placeholder)$/] > Literal[value=/[A-Za-z]{3,}/]',
      message:
        'This attribute is read aloud or shown on hover, so it is user-facing text: put it in ' +
        'src/shared/i18n/messages.en.ts and pass t("some.key"). These are the strings that ' +
        'stay English longest, because they never appear in a screenshot.',
    },
  ],
};
export default tseslint.config(
  {
    ignores: [
      'dist',
      'src/shared/api/identity.d.ts',
      'src/shared/api/streaming.d.ts',
      'src/shared/api/reporting.d.ts',
    ],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,
  reactHooks.configs.flat.recommended,

  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: {
        window: 'readonly',
        document: 'readonly',
        fetch: 'readonly',
        console: 'readonly',
      },
    },
  },

  {
    // The generator and the checks are Node programs, not browser code.
    files: ['scripts/**/*.mjs'],
    languageOptions: {
      globals: { process: 'readonly', console: 'readonly', fetch: 'readonly', URL: 'readonly' },
    },
  },

  {
    // Node orchestration that also hands small functions to `page.evaluate` (T-3.10) -- those
    // run inside the browser it launches, so `document` is real there even though this file is
    // not browser code itself.
    files: ['e2e/**/*.mjs'],
    languageOptions: {
      globals: {
        process: 'readonly', console: 'readonly', fetch: 'readonly', URL: 'readonly',
        URLSearchParams: 'readonly', document: 'readonly',
      },
    },
  },

  {
    // Everything a person reads, minus the player. Test files are excluded below: a test asserting
    // on `en['home.due']` is the point, but one arranging a fixture course called "Fire safety"
    // is not translating anything.
    files: [
      'src/app/**/*.tsx',
      'src/learner/**/*.tsx',
      'src/admin/**/*.tsx',
      'src/shared/design/**/*.tsx',
      'src/shared/state/**/*.tsx',
    ],
    ignores: ['**/*.test.tsx'],
    rules: noEnglishInComponents,
  },

  {
    files: ['src/learner/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['error', {
        patterns: [{
          group: ['**/admin/**', '../admin/*'],
          message:
            'The learner app must not import from the admin console. They share `shared/`; ' +
            'anything else belongs there too (docs/frontend.md).',
        }],
      }],
    },
  },

  {
    files: ['src/player/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['error', {
        patterns: [{
          group: ['**/learner/**', '**/admin/**', '../learner/*', '../admin/*'],
          message:
            'The player is published on its own (T-10.7, ADR-0110) and cannot depend on a ' +
            'screen that will not be published with it. Anything it needs belongs in ' +
            'src/player/ or src/shared/.',
        }],
      }],
    },
  },

  {
    files: ['src/admin/**/*.{ts,tsx}'],
    rules: {
      'no-restricted-imports': ['error', {
        patterns: [{
          group: ['**/learner/**', '../learner/*'],
          message:
            'The admin console must not import from the learner app. They share `shared/`; ' +
            'anything else belongs there too (docs/frontend.md).',
        }],
      }],
    },
  },
);
