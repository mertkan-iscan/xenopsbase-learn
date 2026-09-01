import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

/**
 * The build fails on a lint error, so every rule here is a rule somebody has to obey rather than
 * a suggestion in an editor.
 *
 * The rule that is not standard is the last one: the learner routes and the admin routes may not
 * import each other. They are one deployable on purpose (docs/frontend.md), and the thing that
 * keeps that from quietly becoming one bundle is a boundary the build enforces.
 */
export default tseslint.config(
  { ignores: ['dist', 'src/shared/api/identity.d.ts'] },

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
