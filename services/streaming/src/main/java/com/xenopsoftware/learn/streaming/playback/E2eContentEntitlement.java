package com.xenopsoftware.learn.streaming.playback;

import com.xenopsoftware.learn.streaming.video.VideoAssetRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * T-3.10's stand-in for the catalog wiring {@link UnassignedContent} is still waiting on -- that
 * gap is tracked separately from this issue, and T-3.10 does not own closing it. Proving playback
 * survives our services dying needs one real, entitled video; it does not need a real catalog.
 *
 * <p>Active only under the {@code e2e} profile, and used by nothing but the backend-down playback
 * test. It treats the node id asked about AS a video asset id, so the harness needs no seeding
 * step beyond uploading a video: whatever {@code video_asset} row it creates is immediately
 * "assigned" and "reachable" to any viewer in its tenant.
 */
@Component
@Profile("e2e")
public class E2eContentEntitlement implements ContentEntitlement {

    private final VideoAssetRepository videoAssets;

    public E2eContentEntitlement(VideoAssetRepository videoAssets) {
        this.videoAssets = videoAssets;
    }

    @Override
    public Optional<NodeEntitlement> lookUp(UUID nodeId, Viewer viewer) {
        return videoAssets.findById(nodeId)
            .map(asset -> new NodeEntitlement(nodeId, asset.getId(), true, true, null));
    }
}
