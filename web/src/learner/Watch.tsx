import { useParams } from 'react-router';
import { EmbeddedPlayer } from '../player/EmbeddedPlayer.tsx';
import { ErrorState } from '../shared/state/States.tsx';

/**
 * Watching one thing (T-3.5).
 *
 * <p>The screen is thin on purpose, and it will stay thin. What a learner sees around a video —
 * where they are in a course, what unlocks next, what they have finished — comes from the catalog
 * (T-5.2) and their own progress (T-3.7), and neither exists. What this proves today is the part
 * that does: an entitlement decision, a token, a renewal loop, and the embed boundary, reachable
 * from the real application at `/watch/<node id>`.
 *
 * <p>It takes a node id from the URL rather than offering a picker, because there is nothing to
 * pick from yet. A developer with a node id can watch; when assignments exist, this is the screen
 * they navigate to.
 */
export function Watch() {
  const { nodeId } = useParams();

  if (!nodeId) {
    return <ErrorState message="No video was named in the address." />;
  }

  return (
    <>
      <h1>Watching</h1>
      {/* Through the same iframe and the same loader a customer uses (ADR-0110). Rendering the
          player component directly would be one import shorter and would leave the embed path
          exercised by nobody who would notice it break. */}
      <EmbeddedPlayer nodeId={nodeId} title="Video" />
    </>
  );
}
