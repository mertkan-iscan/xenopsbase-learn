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
 */
export default tseslint.config(
  { ignores: ['dist', 'src/shared/api/identity.d.ts', 'src/shared/api/streaming.d.ts'] },

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
