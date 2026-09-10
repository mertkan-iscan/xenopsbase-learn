import { useCallback, useEffect, useState } from 'react';
import { identity } from '../api/client.ts';

/**
 * Who the caller is, from the one endpoint that knows (T-10.2).
 *
 * <p>`/auth/session` answers three fields — signed in, a display name, a sign-in URL — and the
 * gateway keeps it that small on purpose. Everything else about a person lives in `identity`, and
 * this is the one call that fetches it.
 *
 * <p><b>It is load-bearing rather than cosmetic.</b> Catalog and assessment derive no actor from
 * the token: `AssignRequest.assignedBy` and `PublishRequest.publishedBy` are fields the CLIENT
 * fills in. So authoring and assigning are impossible without `me.id`, and the server takes our
 * word for who acted — which is worth naming as a gap (ADR-0109 / T-9.11) rather than leaving as
 * an oddity somebody discovers while debugging an audit trail.
 *
 * <p><b>403 is a real answer, not a failure.</b> `/api/v1/me` refuses a platform-side caller who
 * has no tenant, and `platform-admin` is exactly that. Such a person is not broken and not signed
 * out; they simply have no learner identity in this company. The union below says so, so the shell
 * can show them the console instead of an error.
 */
export type Me = {
  id: string;
  tenant: string;
  email: string;
  displayName: string;
  status: string;
};

export type WhoAmI =
  | { state: 'asking' }
  | { state: 'tenant'; me: Me }
  /** Signed in, but with no identity inside a company — platform staff. */
  | { state: 'platform' }
  | { state: 'failed' };

export function useMe(enabled: boolean): WhoAmI {
  const [who, setWho] = useState<WhoAmI>({ state: 'asking' });

  const ask = useCallback(() => {
    if (!enabled) {
      return;
    }
    identity
      .GET('/api/v1/me')
      .then(({ data, response }) => {
        if (data) {
          setWho({ state: 'tenant', me: data as Me });
        } else if (response?.status === 403) {
          setWho({ state: 'platform' });
        } else {
          setWho({ state: 'failed' });
        }
      })
      .catch(() => setWho({ state: 'failed' }));
  }, [enabled]);

  useEffect(() => {
    ask();
  }, [ask]);

  return enabled ? who : { state: 'asking' };
}
