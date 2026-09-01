import { useCallback, useEffect, useState } from 'react';
import { failureFrom, identity, type ApiFailure } from '../shared/api/client.ts';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';

type Me = {
  id: string;
  tenant: string;
  email: string;
  displayName: string;
  status: string;
};

/**
 * The learner's own page, and the first screen to prove the three states against a real service
 * (T-10.1). Assigned courses arrive with E5; today it answers "who am I here", which is the one
 * question `identity` can already answer for a learner.
 *
 * <p>One state value rather than three booleans, because three booleans can express states that
 * do not exist — loading and failed at once, ready with no data — and every screen that has them
 * eventually renders one of those by accident.
 */
type Screen =
  | { status: 'loading' }
  | { status: 'ready'; me: Me }
  | { status: 'failed'; failure: ApiFailure };

export function MyLearning() {
  const [screen, setScreen] = useState<Screen>({ status: 'loading' });

  // Nothing here sets state synchronously: the effect starts the request and every transition
  // happens in a callback. That is what keeps the render loop from cascading, and React's lint
  // rule enforces it rather than trusting anybody to remember.
  const load = useCallback(() => {
    identity
      .GET('/api/v1/me')
      .then(({ data, response, error }) => {
        setScreen(
          data
            ? { status: 'ready', me: data as Me }
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

  function retry() {
    setScreen({ status: 'loading' });
    load();
  }

  if (screen.status === 'loading') {
    return <Loading what="your account" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={retry} />;
  }
  return (
    <>
      <h1>My learning</h1>
      <p>
        Signed in as <strong>{screen.me.displayName}</strong> ({screen.me.email}) in{' '}
        <code>{screen.me.tenant}</code>.
      </p>
      <Empty title="Nothing is assigned to you yet.">
        <p>Courses appear here once somebody assigns one (E5).</p>
      </Empty>
    </>
  );
}
