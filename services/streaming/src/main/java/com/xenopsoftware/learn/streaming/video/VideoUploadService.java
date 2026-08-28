package com.xenopsoftware.learn.streaming.video;

import com.xenopsoftware.learn.streaming.media.MediaProvider;
import com.xenopsoftware.learn.streaming.media.UploadRequest;
import com.xenopsoftware.learn.streaming.media.UploadTarget;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues upload targets without a byte ever transiting this service (T-3.2). The order of
 * operations is the design: quota and size are checked against the DECLARED size before the
 * provider is asked for anything, so an over-limit request costs nothing and an issued target
 * is always one the tenant had room for.
 */
@Service
public class VideoUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoUploadService.class);

    private final VideoAssetRepository repository;
    private final MediaProvider mediaProvider;
    private final UploadProperties properties;

    public VideoUploadService(VideoAssetRepository repository, MediaProvider mediaProvider,
            UploadProperties properties) {
        this.repository = repository;
        this.mediaProvider = mediaProvider;
        this.properties = properties;
    }

    public record IssuedUpload(VideoAsset asset, UploadTarget target) {}

    public IssuedUpload createVideo(long maxDurationSeconds, long sizeBytes) {
        enforceLimits(sizeBytes);
        UploadTarget target = mediaProvider.createUploadTarget(
            new UploadRequest(maxDurationSeconds, sizeBytes));
        try {
            VideoAsset asset = repository.save(new VideoAsset(
                mediaProvider.providerId(), target.providerRef(), sizeBytes, maxDurationSeconds,
                target.expiresAt()));
            return new IssuedUpload(asset, target);
        } catch (RuntimeException e) {
            // The provider asset exists but our row does not: without this, the failure leaks
            // a vendor-side object nothing of ours references, invisible to the reaper.
            tryDelete(target.providerRef());
            throw e;
        }
    }

    /**
     * A fresh target for an interrupted-and-expired upload. tus resume handles interruptions
     * within a target's life; this handles the target itself dying. The old provider asset is
     * deleted — the pair (provider, provider_ref) on our row is the ONLY live target, which is
     * what "cannot be reused for a different item" means mechanically: a ref binds to exactly
     * one row, at mint time, forever.
     */
    @Transactional
    public IssuedUpload reissueTarget(UUID assetId) {
        VideoAsset asset = repository.findById(assetId)
            .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        if (asset.getState() != VideoAssetState.PENDING_UPLOAD) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "Upload already " + asset.getState() + "; a finished upload does not get a new target");
        }
        String previousRef = asset.getProviderRef();
        UploadTarget target = mediaProvider.createUploadTarget(new UploadRequest(
            asset.getMaxDurationSeconds(), asset.getSizeBytes()));
        asset.replaceUploadTarget(target.providerRef(), target.expiresAt());
        repository.save(asset);
        tryDelete(previousRef);
        return new IssuedUpload(asset, target);
    }

    private void enforceLimits(long sizeBytes) {
        if (sizeBytes > properties.maxSizeBytes()) {
            throw new UploadLimitException("Declared size " + sizeBytes
                + " exceeds the per-upload ceiling of " + properties.maxSizeBytes() + " bytes");
        }
        long accountable = repository.accountableSizeBytes();
        if (accountable + sizeBytes > properties.tenantQuotaBytes()) {
            throw new QuotaExceededException("This upload would put the tenant at "
                + (accountable + sizeBytes) + " of " + properties.tenantQuotaBytes()
                + " quota bytes; free space by deleting videos (T-3.8) or raise the quota");
        }
    }

    private void tryDelete(String providerRef) {
        try {
            mediaProvider.delete(providerRef);
        } catch (RuntimeException cleanupFailed) {
            // Best-effort: the asset is unreferenced either way, and T-3.8's delete sweep is
            // the backstop. Worth a line so the orphan is findable.
            LOG.warn("Could not delete orphaned provider asset {}", providerRef, cleanupFailed);
        }
    }
}
