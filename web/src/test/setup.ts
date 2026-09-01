import '@testing-library/jest-dom/vitest';
import { expect } from 'vitest';
import * as matchers from 'vitest-axe/matchers';

// Accessibility assertions are ordinary assertions here, available in every test rather than in
// a special suite somebody remembers to run (T-10.1).
expect.extend(matchers);
