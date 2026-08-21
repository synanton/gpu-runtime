package com.synanton.gpu.adapter.out.registry;

import com.synanton.gpu.domain.port.out.ArtifactResolver;
import com.synanton.gpu.domain.port.out.ExecutionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * ArtifactResolver with distributed coordination via PostgreSQL advisory locking.
 *
 * <p>Guarantees exactly one artifact download per (model_id, digest) pair, even when multiple
 * Gateway instances race to serve the first request for a model:
 * <ol>
 *   <li>Fast path: check {@code artifact_cache} table — return immediately if cached.</li>
 *   <li>Acquire session-level advisory lock on {@code hashtext(model_id || ':' || digest)}.</li>
 *   <li>Mandatory second cache check inside the lock — another instance may have completed.</li>
 *   <li>Download to {@code .staging/{model_id}/{digest}/} if still absent.</li>
 *   <li>Verify SHA-256 digest against the expected value.</li>
 *   <li>Atomic rename: {@code .staging} → final path {@code {model_id}/{digest}/}.</li>
 *   <li>Record in {@code artifact_cache}; release advisory lock.</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "gpu-gateway.dispatch.strategy", havingValue = "vllm")
@Slf4j
public class SecureRegistryFetcher implements ArtifactResolver {

    private final ArtifactCacheRepository cacheRepository;
    private final ExecutionRepository executionRepository;
    private final Path modelCacheRoot;
    private final String registryBaseUrl;
    private final HttpClient httpClient;

    public SecureRegistryFetcher(ArtifactCacheRepository cacheRepository,
                                  ExecutionRepository executionRepository,
                                  Path modelCacheRoot,
                                  String registryBaseUrl) {
        this.cacheRepository = cacheRepository;
        this.executionRepository = executionRepository;
        this.modelCacheRoot = modelCacheRoot;
        this.registryBaseUrl = registryBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String resolve(String modelId, String digest) {
        // 1. Fast path: already cached — no lock needed
        String cachedPath = cacheRepository.findLocalPath(modelId, digest).orElse(null);
        if (cachedPath != null) {
            log.debug("Artifact cache hit: model={} digest={}", modelId, digest);
            return cachedPath;
        }

        long lockKey = lockKeyFor(modelId, digest);
        executionRepository.acquireAdvisoryLock(lockKey);
        try {
            // 2. Mandatory second check inside the lock — another instance may have completed
            cachedPath = cacheRepository.findLocalPath(modelId, digest).orElse(null);
            if (cachedPath != null) {
                log.debug("Artifact cache hit after lock: model={} digest={}", modelId, digest);
                return cachedPath;
            }

            // 3. Download → verify → publish atomically
            String localPath = downloadAndPublish(modelId, digest);
            cacheRepository.recordPublished(modelId, digest, localPath);
            log.info("Artifact published: model={} digest={} path={}", modelId, digest, localPath);
            return localPath;

        } finally {
            executionRepository.releaseAdvisoryLock(lockKey);
        }
    }

    private String downloadAndPublish(String modelId, String digest) {
        Path stagingDir = modelCacheRoot.resolve(".staging").resolve(modelId).resolve(digest);
        Path finalDir = modelCacheRoot.resolve(modelId).resolve(digest);

        try {
            Files.createDirectories(stagingDir);
            Path stagingFile = stagingDir.resolve("model.bin");

            String downloadUrl = buildDownloadUrl(modelId, digest);
            log.info("Downloading artifact: model={} url={}", modelId, downloadUrl);

            String computedDigest = downloadWithDigest(downloadUrl, stagingFile);
            verifyDigest(digest, computedDigest, modelId);

            // Atomic publication: rename staging dir to final location
            Files.createDirectories(finalDir.getParent());
            Files.move(stagingDir, finalDir, StandardCopyOption.ATOMIC_MOVE);

            return finalDir.toAbsolutePath().toString();

        } catch (IOException e) {
            cleanup(stagingDir);
            throw new ArtifactDownloadException(modelId, digest, "Download failed: " + e.getMessage(), e);
        }
    }

    private String downloadWithDigest(String url, Path destination) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofMinutes(30))
                .build();

        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                throw new IOException("Registry returned HTTP " + response.statusCode() + " for URL: " + url);
            }

            try (DigestInputStream digestStream = new DigestInputStream(response.body(), sha256)) {
                Files.copy(digestStream, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return "sha256:" + HexFormat.of().formatHex(sha256.digest());

        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private void verifyDigest(String expected, String actual, String modelId) {
        if (!expected.equals(actual)) {
            throw new ArtifactIntegrityException(modelId,
                    "Digest mismatch: expected=" + expected + " actual=" + actual);
        }
        log.debug("Digest verified: {}", actual);
    }

    private String buildDownloadUrl(String modelId, String digest) {
        return registryBaseUrl + "/models/" + modelId + "/artifacts/" + digest;
    }

    private long lockKeyFor(String modelId, String digest) {
        // Stable integer key from the composite (model_id, digest) string
        return (long) (modelId + ":" + digest).hashCode();
    }

    private void cleanup(Path stagingDir) {
        try {
            if (Files.exists(stagingDir)) {
                Files.walk(stagingDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
            }
        } catch (IOException e) {
            log.warn("Failed to clean up staging dir {}: {}", stagingDir, e.getMessage());
        }
    }

    /** Thrown when an artifact download fails at the network or IO level. */
    @SuppressWarnings("serial")
    public static class ArtifactDownloadException extends RuntimeException {
        public ArtifactDownloadException(String modelId, String digest, String message, Throwable cause) {
            super("Artifact download failed for model=" + modelId + " digest=" + digest + ": " + message, cause);
        }
    }

    /** Thrown when a downloaded artifact's digest does not match the expected value. */
    @SuppressWarnings("serial")
    public static class ArtifactIntegrityException extends RuntimeException {
        public ArtifactIntegrityException(String modelId, String message) {
            super("Artifact integrity failure for model=" + modelId + ": " + message);
        }
    }
}
