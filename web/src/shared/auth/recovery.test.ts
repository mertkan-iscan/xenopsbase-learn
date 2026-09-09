import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { parkWork, takeParkedWork, withSessionRecovery } from './recovery.ts';

/**
 * The case T-10.2 says will otherwise be found by a customer: a forty-minute exam submitted into a
 * session that ended thirty seconds ago.
 *
 * The last test is that scenario end to end. It is worth reading as a sequence rather than as an
 * assertion: submit, refused, work parked, person signs in again, work replayed, accepted. Nothing
 * in that sequence involves a token, which is why it can be written at all.
 */
describe('work that must survive signing in again', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    vi.stubGlobal('location', { assign: vi.fn(), pathname: '/exam/42' });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('parks the payload when the session has ended', async () => {
    const outcome = await withSessionRecovery(
      { label: 'exam-submission', payload: { answers: [1, 2, 3] } },
      async () => ({ response: new Response(null, { status: 401 }) }),
    );

    expect(outcome).toEqual({ kind: 'session-ended' });
    expect(takeParkedWork('exam-submission')).toMatchObject({
      payload: { answers: [1, 2, 3] },
      route: '/exam/42',
    });
  });

  it('does not park work a sign-in cannot rescue', async () => {
    // A 403 is "not yours to do". Parking it would leave a submission in storage that nobody will
    // ever replay, and a prompt to sign in that changes nothing.
    const outcome = await withSessionRecovery(
      { label: 'exam-submission', payload: { answers: [1] } },
      async () => ({ response: new Response(null, { status: 403 }) }),
    );

    expect(outcome.kind).toBe('failed');
    expect(takeParkedWork()).toBeNull();
  });

  it('hands parked work over exactly once', () => {
    parkWork('exam-submission', { answers: [7] });

    expect(takeParkedWork('exam-submission')).not.toBeNull();
    // Twice would be a submission sent twice, which for an attempt with one allowed try is worse
    // than losing it.
    expect(takeParkedWork('exam-submission')).toBeNull();
  });

  it('only gives a screen its own work', () => {
    parkWork('exam-submission', { answers: [7] });

    expect(takeParkedWork('profile-edit')).toBeNull();
    expect(takeParkedWork('exam-submission')).not.toBeNull();
  });

  it('survives a browser that refuses storage rather than losing the sign-in with it', async () => {
    const refuse = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage is disabled');
    });

    const outcome = await withSessionRecovery(
      { label: 'exam-submission', payload: { answers: [1] } },
      async () => ({ response: new Response(null, { status: 401 }) }),
    );

    // The work is lost, which is bad; throwing here would also have lost the sign-in that is
    // about to happen, which is worse.
    expect(outcome).toEqual({ kind: 'session-ended' });
    refuse.mockRestore();
  });

  it('the forty-minute exam: refused, parked, signed in again, replayed, accepted', async () => {
    const answers = { attemptId: 'a-42', answers: [1, 4, 2, 3] };
    const send = vi
      .fn()
      .mockResolvedValueOnce({ response: new Response(null, { status: 401 }) })
      .mockResolvedValueOnce({ response: new Response(null, { status: 200 }) });

    const refused = await withSessionRecovery({ label: 'exam-submission', payload: answers }, send);
    expect(refused).toEqual({ kind: 'session-ended' });

    // The person signs in. A full navigation to the issuer and back, so everything in memory is
    // gone -- which is exactly what the parking survives.
    const parked = takeParkedWork('exam-submission');
    expect(parked?.payload).toEqual(answers);
    expect(parked?.route).toBe('/exam/42');

    const replayed = await withSessionRecovery(
      { label: 'exam-submission', payload: parked?.payload },
      send,
    );
    expect(replayed.kind).toBe('done');
    expect(send).toHaveBeenCalledTimes(2);
  });
});
