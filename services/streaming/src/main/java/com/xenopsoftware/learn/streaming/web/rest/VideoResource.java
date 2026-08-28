package com.xenopsoftware.learn.streaming.web.rest;

import com.xenopsoftware.learn.streaming.video.VideoAsset;
import com.xenopsoftware.learn.streaming.video.VideoAssetRepository;
import com.xenopsoftware.learn.streaming.video.VideoUploadService;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Videos and their upload targets (T-3.2). Note what is absent: no endpoint here accepts a byte
 * of video. The client uploads directly to the returned target, resumably; this service's part
 * ends at issuing it. Multipart is disabled service-wide and an ArchUnit rule keeps it out, so
 * the convenience version cannot be quietly reintroduced.
 */
@RestController
@RequestMapping("/api/v1")
public class VideoResource {

    private final VideoUploadService uploadService;
    private final VideoAssetRepository repository;

    public VideoResource(VideoUploadService uploadService, VideoAssetRepository repository) {
        this.uploadService = uploadService;
        this.repository = repository;
    }

    public record CreateVideoRequest(@Positive long maxDurationSeconds, @Positive long sizeBytes) {}

    public record IssuedUploadResponse(UUID id, String state, URI uploadUrl, Instant uploadExpiresAt) {}

    public record VideoView(UUID id, String state, Double durationSeconds, long sizeBytes) {}

    @PostMapping("/videos")
    public IssuedUploadResponse create(@RequestBody CreateVideoRequest request) {
        VideoUploadService.IssuedUpload issued =
            uploadService.createVideo(request.maxDurationSeconds(), request.sizeBytes());
        return toResponse(issued);
    }

    /**
     * A fresh target for an upload whose previous target expired. tus handles resumption
     * within a live target; this handles the target itself dying.
     */
    @PostMapping("/videos/{id}/upload-target")
    public IssuedUploadResponse reissue(@PathVariable UUID id) {
        return toResponse(uploadService.reissueTarget(id));
    }

    @GetMapping("/videos/{id}")
    public VideoView video(@PathVariable UUID id) {
        // Tenant-filtered by the persistence layer: another tenant's id is not found, which is
        // the 404-not-403 shape ADR-0102's isolation sentence promises.
        return repository.findById(id)
            .map(asset -> new VideoView(asset.getId(), asset.getState().name(),
                asset.getDurationSeconds(), asset.getSizeBytes()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static IssuedUploadResponse toResponse(VideoUploadService.IssuedUpload issued) {
        VideoAsset asset = issued.asset();
        return new IssuedUploadResponse(asset.getId(), asset.getState().name(),
            issued.target().uploadUrl(), issued.target().expiresAt());
    }
}
