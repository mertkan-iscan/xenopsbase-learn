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
  /**
   * The language they read this product in, or null/absent when they have not told us.
   *
   * <p><b>Optional because it is nullable in three different ways</b>, and only one of them is
   * about the person. `app_user.language` is nullable — "has not told us" is a findable population
   * and "chose English" is not (V13's comment makes the same argument for the timezone). It is
   * also absent entirely from an identity that has not yet been deployed with the column. Both
   * resolve the same way here: fall through to what the browser prefers. Nothing downstream may
   * treat either as a choice of English.
   *
   * <p>The column this describes was added by `V14__user_preferences.sql`. Until then it did not
   * exist and this field was never populated, which is worth knowing if you are reading a bug
   * report from before that migration: the product answered in whatever the browser guessed, for
   * everybody, however many times they had told us otherwise.
   */
  language?: string | null;

  /**
   * Which palette they read the product in: `LIGHT`, `DARK`, `SYSTEM`, or null/absent when they
   * have not told us (T-10.9).
   *
   * <p>The enum's own name, uppercase, the way identity carries every closed set. `themeFrom` in
   * `shared/theme/theme.ts` is the one place that converts it to the lowercase form a browser
   * uses, and nothing else may.
   *
   * <p><b>Null is not `SYSTEM`.</b> They render identically — both follow the operating system —
   * and they are different answers: one is somebody who tried dark and went back, the other is
   * somebody who has never been asked. Nothing downstream may treat the absence as a choice.
   */
  theme?: string | null;
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
