package com.xenopsoftware.learn.streaming.web.rest;

import com.xenopsoftware.learn.streaming.video.VideoAsset;
import com.xenopsoftware.learn.streaming.video.VideoAssetRepository;
import com.xenopsoftware.learn.streaming.video.VideoDeletionService;
import com.xenopsoftware.learn.streaming.video.VideoUploadService;
import com.xenopsoftware.learn.common.web.ProblemDocumentation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final VideoDeletionService deletionService;

    public VideoResource(VideoUploadService uploadService, VideoAssetRepository repository,
            VideoDeletionService deletionService) {
        this.uploadService = uploadService;
        this.repository = repository;
        this.deletionService = deletionService;
    }

    public record CreateVideoRequest(@Positive long maxDurationSeconds, @Positive long sizeBytes) {}

    public record IssuedUploadResponse(UUID id, String state, URI uploadUrl, Instant uploadExpiresAt) {}

    public record VideoView(UUID id, String state, Double durationSeconds, long sizeBytes) {}

    /** Why this video is being deleted. Required — see {@link VideoDeletionService}. */
    public record DeleteVideoRequest(String reason) {}

    /**
     * What a deletion request answers with.
     *
     * @param state DELETING until the provider confirms, DELETED once it has. The distinction is
     *              the point: a caller is told the bytes are gone only when they are
     * @param deletedAt null until then
     */
    public record DeletionView(UUID id, String state, Instant requestedAt, Instant deletedAt) {}

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

    /**
     * Ask for a video to be deleted.
     *
     * <p>202 rather than 204, and that is the honest status: this accepts the request, stops the
     * video playing and queues the row. The bytes go when the provider confirms, which is a
     * different moment, and a 204 would claim it had already happened (T-3.8).
     *
     * <p>DELETE with a body, deliberately. The reason is the entire audit record of the operation,
     * and putting it in a query string would put a customer's stated reason in every access log
     * between here and the client.
     */
    @DeleteMapping("/videos/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ApiResponse(responseCode = "202",
        description = "The deletion was accepted; the video no longer plays")
    @ApiResponse(responseCode = "400", description = "No reason was given",
        content = @Content(mediaType = ProblemDocumentation.PROBLEM_JSON,
            schema = @Schema(ref = ProblemDocumentation.REF)))
    @ApiResponse(responseCode = "404", description = "No such video in this company",
        content = @Content)
    public DeletionView delete(@PathVariable UUID id, @RequestBody DeleteVideoRequest request) {
        VideoAsset asset = deletionService.requestDeletion(id, request.reason());
        return new DeletionView(asset.getId(), asset.getState().name(),
            asset.getDeletionRequestedAt(), asset.getDeletedAt());
    }

    private static IssuedUploadResponse toResponse(VideoUploadService.IssuedUpload issued) {
        VideoAsset asset = issued.asset();
        return new IssuedUploadResponse(asset.getId(), asset.getState().name(),
            issued.target().uploadUrl(), issued.target().expiresAt());
    }
}
