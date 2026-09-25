package org.synanton.gpu.adapter.out.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.synanton.gpu.config.GpuGatewayProperties;

import java.io.File;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T-K8S-6a: key loading (fail closed), ES256 signing, JWKS listener, readiness indicator. */
class ExecutionJwtTest {

    @TempDir Path dir;
    private JwksServer server;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private static KeyPair pair(String curve) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec(curve));
        return g.generateKeyPair();
    }

    private static String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + label + "-----\n";
    }

    private void write(String name, String content) throws Exception {
        Files.writeString(dir.resolve(name), content);
    }

    /** A valid Secret layout: current.key + current.pub + previous.pub. */
    private KeyPair[] validDir() throws Exception {
        KeyPair cur = pair("secp256r1"), prev = pair("secp256r1");
        write("current.key", pem("PRIVATE KEY", cur.getPrivate().getEncoded()));
        write("current.pub", pem("PUBLIC KEY", cur.getPublic().getEncoded()));
        write("previous.pub", pem("PUBLIC KEY", prev.getPublic().getEncoded()));
        return new KeyPair[]{cur, prev};
    }

    // ─── key loading ─────────────────────────────────────────────────────────

    @Test
    void loadsAValidSecretAndDerivesRfc7638Kids() throws Exception {
        KeyPair[] kp = validDir();
        Files.createDirectory(dir.resolve("..data")); // Kubernetes Secret volume artefact: ignored
        ExecutionJwtKeys keys = ExecutionJwtKeys.load(dir);

        ECPublicKey cur = (ECPublicKey) kp[0].getPublic();
        String canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + ExecutionJwtKeys.coordinate(cur.getW().getAffineX())
                + "\",\"y\":\"" + ExecutionJwtKeys.coordinate(cur.getW().getAffineY()) + "\"}";
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        assertThat(keys.currentKid()).isEqualTo(expected).hasSize(43);
        assertThat(keys.previousKid()).isNotEqualTo(keys.currentKid());
        assertThat(keys.toString()).doesNotContain("PRIVATE").contains(keys.currentKid());
    }

    @Test
    void coordinatesArePaddedTo32Bytes() {
        assertThat(B64.decode(ExecutionJwtKeys.coordinate(BigInteger.ONE))).hasSize(32);
        assertThat(B64.decode(ExecutionJwtKeys.coordinate(BigInteger.TWO.pow(255)))).hasSize(32); // sign byte dropped
    }

    @Test
    void failsClosedOnEveryBrokenLayout() throws Exception {
        assertThatThrownBy(() -> ExecutionJwtKeys.load(dir.resolve("missing"))).hasMessageContaining("not a directory");

        validDir();
        Files.delete(dir.resolve("previous.pub"));
        assertThatThrownBy(() -> ExecutionJwtKeys.load(dir)).hasMessageContaining("exactly two public keys");

        validDir();
        write("extra.pub", Files.readString(dir.resolve("current.pub")));
        assertThatThrownBy(() -> ExecutionJwtKeys.load(dir)).hasMessageContaining("exactly two public keys");
        Files.delete(dir.resolve("extra.pub"));

        write("previous.pub", Files.readString(dir.resolve("current.pub")));
        assertThatThrownBy(() -> ExecutionJwtKeys.load(dir)).hasMessageContaining("same key");

        validDir();
        write("current.pub", pem("PUBLIC KEY", pair("secp256r1").getPublic().getEncoded())); // not current.key's
        assertThatThrownBy(() -> ExecutionJwtKeys.load(dir)).hasMessageContaining("not a key pair");

        validDir();
        KeyPair p384 = pair("secp384r1");
        write("current.key", pem("PRIVATE KEY", p384.getPrivate().getEncoded()));
        assertThatThrownBy(() -> ExecutionJwtKeys.load(dir)).hasMessageContaining("P-256");

        validDir();
        write("current.key", "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n");
        assertThatThrownBy(() -> ExecutionJwtKeys.load(dir)).hasMessageContaining("SEC1").hasMessageContaining("PKCS#8");
    }

    @Test
    void acceptsKeysMadeWithTheRunbookOpensslCommands() throws Exception {
        Assumptions.assumeTrue(new File("/usr/bin/openssl").canExecute() || new File("/usr/local/bin/openssl").canExecute(),
                "openssl required");
        for (String k : new String[]{"current", "previous"}) {
            openssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", k + ".key");
            openssl("pkey", "-in", k + ".key", "-pubout", "-out", k + ".pub");
        }
        Files.delete(dir.resolve("previous.key")); // only the current private key is deployed
        assertThat(ExecutionJwtKeys.load(dir).currentKid()).hasSize(43);
    }

    private void openssl(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("openssl"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException(out);
    }

    // ─── signing, verified through the published JWKS ────────────────────────

    private static ECPublicKey fromJwk(JsonNode jwk) throws Exception {
        AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        ECPoint w = new ECPoint(new BigInteger(1, B64.decode(jwk.get("x").asText())), new BigInteger(1, B64.decode(jwk.get("y").asText())));
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, ap.getParameterSpec(ECParameterSpec.class)));
    }

    @Test
    void signsEs256TokensThatVerifyAgainstTheJwks() throws Exception {
        validDir();
        ExecutionJwtKeys keys = ExecutionJwtKeys.load(dir);
        Clock clock = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC);
        var signer = new ExecutionJwtSigner(keys, "synanton-gpu-gateway", "gpu-plane-execution", 60, clock);
        byte[] body = "{\"model\":\"synanton-bge-base-embedding\",\"input\":[\"a\"]}".getBytes(StandardCharsets.UTF_8);

        String jwt = signer.sign(new ExecutionJwtSigner.Subject("EMBED", "synanton-bge-base-embedding",
                "rb-fixed-g5", "req-1", "synanton-benchmark"), body);
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode header = JSON.readTree(B64.decode(parts[0]));
        JsonNode claims = JSON.readTree(B64.decode(parts[1]));
        assertThat(header.get("alg").asText()).isEqualTo("ES256");
        assertThat(header.get("kid").asText()).isEqualTo(keys.currentKid());
        assertThat(claims.get("iss").asText()).isEqualTo("synanton-gpu-gateway");
        assertThat(claims.get("aud").asText()).isEqualTo("gpu-plane-execution");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(60);
        assertThat(claims.get("sub").asText()).isEqualTo("synanton-benchmark");
        assertThat(claims.get("tenant_id").asText()).isEqualTo("rb-fixed-g5");
        assertThat(claims.get("op").asText()).isEqualTo("EMBED");
        assertThat(claims.get("body_sha256").asText()).isEqualTo(
                Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(claims.get("jti").asText()).isNotBlank();

        JsonNode jwks = JSON.readTree(keys.jwksJson());
        JsonNode current = null;
        for (JsonNode k : jwks.get("keys")) if (k.get("kid").asText().equals(header.get("kid").asText())) current = k;
        assertThat(current).isNotNull();
        assertThat(jwks.get("keys")).hasSize(2);
        Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
        v.initVerify(fromJwk(current));
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(v.verify(B64.decode(parts[2]))).isTrue();
        assertThat(B64.decode(parts[2])).hasSize(64); // raw r||s, not DER
    }

    @Test
    void emptyBodyHashIsTheSha256OfNothing() {
        assertThat(ExecutionJwtSigner.bodySha256(null)).isEqualTo("47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU");
    }

    // ─── JWKS listener + readiness ───────────────────────────────────────────

    @Test
    void jwksListenerServesOnlyTheJwks() throws Exception {
        validDir();
        ExecutionJwtKeys keys = ExecutionJwtKeys.load(dir);
        server = new JwksServer(0, keys);
        server.start();
        HttpClient http = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + server.boundPort();

        var ok = http.send(HttpRequest.newBuilder(URI.create(base + JwksServer.PATH)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("max-age=300"));
        assertThat(JSON.readTree(ok.body()).get("keys")).hasSize(2);
        assertThat(ok.body()).doesNotContain("\"d\"");  // public keys only

        assertThat(http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
        assertThat(http.send(HttpRequest.newBuilder(URI.create(base + JwksServer.PATH))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(405);
    }

    @Test
    void readinessIsUpOnlyWhenJwksListens() throws Exception {
        var cfg = new GpuGatewayProperties.ExecutionJwt();
        assertThat(new ExecutionJwtHealthIndicator(cfg, provider(null)).health().getStatus()).isEqualTo(Status.UP);

        cfg.setEnabled(true);
        validDir();
        server = new JwksServer(0, ExecutionJwtKeys.load(dir));
        assertThat(new ExecutionJwtHealthIndicator(cfg, provider(server)).health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
        server.start();
        assertThat(new ExecutionJwtHealthIndicator(cfg, provider(server)).health().getStatus()).isEqualTo(Status.UP);
        assertThat(new ExecutionJwtHealthIndicator(cfg, provider(null)).health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<JwksServer> provider(JwksServer s) {
        ObjectProvider<JwksServer> p = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(s);
        return p;
    }
}
