package com.xenopsoftware.learn.streaming.video;

import com.xenopsoftware.learn.streaming.playback.ViewerDirectory;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Asking for a video to be deleted (T-3.8).
 *
 * <h2>The request is not the deletion, and the order is the guarantee</h2>
 *
 * <p>This method writes DELETING and returns. It does not call the provider, and that is
 * deliberate rather than lazy:
 *
 * <ul>
 *   <li><b>Playback has to stop now.</b> The customer's expectation starts at the request, not at
 *       whatever time the vendor's API happens to answer. A deletion honoured only as fast as a
 *       third party responds is one that is unhonoured during exactly the incident that prompted
 *       it.
 *   <li><b>A provider call inside a request transaction is a call that can leave the two
 *       disagreeing.</b> If the call succeeds and the transaction then rolls back, the row says
 *       READY and the bytes are gone — the one direction nothing can detect afterwards, because a
 *       missing asset with a healthy row looks identical to a healthy system until a learner
 *       presses play.
 * </ul>
 *
 * <p>So the row is the queue. {@link DeletionReconciler} drains it, retries until the provider
 * confirms, and only then is the claim "this is deleted" made anywhere.
 *
 * <h2>What is deliberately not enforced here</h2>
 *
 * <p>T-3.8 asks for deletion to be <b>refused or explicitly cascaded when the item is referenced by
 * a course, an assignment or an attempt</b>. This service cannot answer that question: courses and
 * assignments are catalog's rows in catalog's database, attempts are T-6.6's, and streaming holds
 * no reference to any of them — it deliberately does not even know what an item is (T-3.7).
 *
 * <p>Nothing is invented in the meantime. A local "is it referenced" check written here would be
 * one that answers no for reasons that have nothing to do with the truth, and an endpoint that
 * trusted it would be worse than one that never asked. What the tombstone does provide is that
 * being wrong is survivable in one direction: a course pointing at a DELETED asset gets a clear
 * answer rather than a dangling id.
 */
@Service
public class VideoDeletionService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoDeletionService.class);

    private final VideoAssetRepository assets;
    private final ViewerDirectory viewers;

    public VideoDeletionService(VideoAssetRepository assets, ViewerDirectory viewers) {
        this.assets = assets;
        this.viewers = viewers;
    }

    /**
     * Record the request, stop the video playing, and hand the row to the reconciler.
     *
     * @param reason why, for the operator reading this row in a year. Required: a deletion with no
     *               stated reason is one nobody can distinguish from a mistake, and this row is the
     *               whole audit record of the operation.
     */
    @Transactional
    public VideoAsset requestDeletion(UUID assetId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Deleting a video needs a reason. It is the only record of why the bytes went, and "
                    + "a year from now it is the difference between a deletion and an accident.");
        }

        VideoAsset asset = assets.findById(assetId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "No such video"));

        if (asset.getState() == VideoAssetState.DELETED) {
            // Already done, and saying so is more useful than a second no-op 202: a caller
            // retrying a request that already succeeded should learn that it did.
            return asset;
        }

        UUID actor = viewers.currentAppUserId().orElse(null);
        asset.deletionRequested(actor, reason);
        LOG.info("Video {} marked for deletion by {}: {}", assetId, actor, reason);
        return assets.save(asset);
    }
}
