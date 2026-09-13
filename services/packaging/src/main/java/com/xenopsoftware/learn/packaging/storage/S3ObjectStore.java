package com.xenopsoftware.learn.packaging.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * The S3 adapter — the whole footprint of object storage in this service (T-4.1).
 *
 * <p>Plain S3 by construction: {@code GET}, {@code PUT}, {@code HEAD}, {@code LIST},
 * {@code DELETE} and one presigned {@code PUT}. Nothing here is an AWS feature, which is what
 * makes the local stack faithful rather than an approximation — MinIO answers all six.
 */
@Component
public class S3ObjectStore implements ObjectStore {

    private final S3Client client;
    private final S3Presigner presigner;

    public S3ObjectStore(StorageProperties properties) {
        Region region = Region.of(properties.region());
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        // Path style, because MinIO's buckets are a path segment. Virtual-host style would need
        // wildcard DNS for <bucket>.<host>, which a laptop does not have and which would make the
        // local stack differ from production in the one place this adapter is exercised.
        S3Configuration serviceConfiguration = S3Configuration.builder()
            .pathStyleAccessEnabled(properties.pathStyle())
            .build();
        URI endpoint = URI.create(properties.endpoint());
        this.client = S3Client.builder()
            .region(region)
            .credentialsProvider(credentials)
            .endpointOverride(endpoint)
            .serviceConfiguration(serviceConfiguration)
            .build();
        this.presigner = S3Presigner.builder()
            .region(region)
            .credentialsProvider(credentials)
            .endpointOverride(endpoint)
            .serviceConfiguration(serviceConfiguration)
            .build();
    }

    @Override
    public URI presignPut(String bucket, String key, long contentLength, Duration validFor) {
        /*
         * CONTENT-LENGTH IS SIGNED, and that is the difference between an upload target and a
         * standing write permission.
         *
         * The size is checked against this tenant's limits before a target is minted, exactly as
         * streaming checks quota before asking its provider for one. That check would be
         * decorative if the signed URL then accepted any number of bytes: a client that declared
         * ten megabytes could send ten gigabytes and the ceiling would exist only in a comment.
         * Signing the header means storage itself refuses the mismatch, before anything is stored.
         */
        PutObjectRequest put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentLength(contentLength)
            .build();
        return URI.create(presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(validFor)
                .putObjectRequest(put)
                .build())
            .url()
            .toString());
    }

    @Override
    public Optional<Retrieved> get(String bucket, String key) {
        try {
            ResponseInputStream<GetObjectResponse> body = client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
            GetObjectResponse response = body.response();
            return Optional.of(new Retrieved(body,
                response.contentLength() == null ? -1 : response.contentLength(),
                response.contentType()));
        } catch (NoSuchKeyException absent) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Stored> head(String bucket, String key) {
        try {
            HeadObjectResponse response = client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(new Stored(key,
                response.contentLength() == null ? -1 : response.contentLength(),
                response.contentType()));
        } catch (software.amazon.awssdk.services.s3.model.S3Exception maybeAbsent) {
            // HEAD on a missing key answers 404 with NO BODY, so the SDK cannot parse an error
            // code out of it and raises a bare S3Exception rather than NoSuchKeyException. That
            // is why this catches the general type and then insists on the status: anything that
            // is not a 404 is a real failure -- a wrong bucket, a bad credential -- and
            // swallowing it here would report an empty package store as an empty package.
            if (maybeAbsent.statusCode() != 404) {
                throw maybeAbsent;
            }
            return Optional.empty();
        }
    }

    @Override
    public void put(String bucket, String key, InputStream body, long length, String contentType) {
        client.putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromInputStream(body, length));
    }

    @Override
    public void deletePrefix(String bucket, String prefix) {
        String continuation = null;
        do {
            ListObjectsV2Response listing = client.listObjectsV2(ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .continuationToken(continuation)
                .build());
            if (!listing.contents().isEmpty()) {
                client.deleteObjects(DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder()
                        .objects(listing.contents().stream()
                            .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                            .toList())
                        .build())
                    .build());
            }
            // A package with more than a thousand files pages, and a delete that stopped at the
            // first page would leave the rest behind while the row claimed DELETED.
            continuation = Boolean.TRUE.equals(listing.isTruncated())
                ? listing.nextContinuationToken()
                : null;
        } while (continuation != null);
    }
}
