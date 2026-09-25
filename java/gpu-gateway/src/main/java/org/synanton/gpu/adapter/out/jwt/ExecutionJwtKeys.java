package org.synanton.gpu.adapter.out.jwt;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

/**
 * GPU-5 execution-JWT key material (Deployment Plan §12, T-K8S-6a), loaded from a
 * file-mounted Kubernetes Secret directory:
 *
 * <ul>
 *   <li>{@code current.key}: PKCS#8 PEM, EC P-256 private key; the only signing key;</li>
 *   <li>{@code current.pub}, {@code previous.pub}: SPKI PEM public keys, both published in the
 *       JWKS. The spec requires exactly two active keys, current and previous.</li>
 * </ul>
 *
 * <p>Every problem throws {@link IllegalStateException} with a message that names the file but
 * never includes key contents. The Gateway fails to start (fail closed, Plan §5.5). The
 * {@code kid} of each key is its RFC 7638 JWK thumbprint.
 */
public final class ExecutionJwtKeys {

    public static final String CURRENT_KEY = "current.key";
    public static final String CURRENT_PUB = "current.pub";
    public static final String PREVIOUS_PUB = "previous.pub";

    private static final ECParameterSpec P256 = p256();

    private final PrivateKey signingKey;
    private final ECPublicKey currentPublic;
    private final ECPublicKey previousPublic;
    private final String currentKid;
    private final String previousKid;

    private ExecutionJwtKeys(PrivateKey signingKey, ECPublicKey currentPublic, ECPublicKey previousPublic) {
        this.signingKey = signingKey;
        this.currentPublic = currentPublic;
        this.previousPublic = previousPublic;
        this.currentKid = thumbprint(currentPublic);
        this.previousKid = thumbprint(previousPublic);
    }

    public static ExecutionJwtKeys load(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IllegalStateException("execution-jwt key-dir " + dir + " is not a directory "
                    + "(mount the gpu-gateway-jwt-keys Secret; Deployment Plan §12)");
        }
        List<String> pubs;
        try (Stream<Path> files = Files.list(dir)) {
            // Kubernetes Secret volumes also contain ..data / ..<timestamp> entries: skip dot files
            pubs = files.map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith(".") && n.endsWith(".pub")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list execution-jwt key-dir " + dir, e);
        }
        if (!pubs.equals(List.of(CURRENT_PUB, PREVIOUS_PUB))) {
            throw new IllegalStateException("execution-jwt key-dir must hold exactly two public keys, "
                    + CURRENT_PUB + " and " + PREVIOUS_PUB + " (Deployment Plan §12: current + previous); found " + pubs);
        }
        PrivateKey priv = readPrivate(dir.resolve(CURRENT_KEY));
        ECPublicKey cur = readPublic(dir.resolve(CURRENT_PUB));
        ECPublicKey prev = readPublic(dir.resolve(PREVIOUS_PUB));
        if (Arrays.equals(cur.getEncoded(), prev.getEncoded())) {
            throw new IllegalStateException(CURRENT_PUB + " and " + PREVIOUS_PUB + " are the same key; "
                    + "generate a distinct previous key pair");
        }
        requirePair(priv, cur);
        return new ExecutionJwtKeys(priv, cur, prev);
    }

    public PrivateKey signingKey() { return signingKey; }
    public ECPublicKey currentPublic() { return currentPublic; }
    public ECPublicKey previousPublic() { return previousPublic; }
    public String currentKid() { return currentKid; }
    public String previousKid() { return previousKid; }

    // ─── PEM / key parsing ──────────────────────────────────────────────────

    private static byte[] pemBody(Path file, String expectedLabel) {
        String pem;
        try {
            pem = Files.readString(file, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read execution-jwt key file " + file.getFileName(), e);
        }
        if (pem.contains("-----BEGIN EC PRIVATE KEY-----")) {
            throw new IllegalStateException(file.getFileName() + " is a SEC1 'EC PRIVATE KEY'; PKCS#8 is required. "
                    + "Convert: openssl pkcs8 -topk8 -nocrypt -in <file> -out " + CURRENT_KEY);
        }
        String begin = "-----BEGIN " + expectedLabel + "-----";
        String end = "-----END " + expectedLabel + "-----";
        int b = pem.indexOf(begin);
        int e = pem.indexOf(end);
        if (b < 0 || e < b) {
            throw new IllegalStateException(file.getFileName() + " is not a PEM '" + expectedLabel + "'");
        }
        try {
            return Base64.getMimeDecoder().decode(pem.substring(b + begin.length(), e));
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(file.getFileName() + " has an invalid PEM body");
        }
    }

    private static PrivateKey readPrivate(Path file) {
        try {
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pemBody(file, "PRIVATE KEY")));
            requireP256(((ECPrivateKey) key).getParams(), file);
            return key;
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException(file.getFileName() + " is not a PKCS#8 EC private key");
        }
    }

    private static ECPublicKey readPublic(Path file) {
        try {
            ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(pemBody(file, "PUBLIC KEY")));
            requireP256(key.getParams(), file);
            return key;
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException(file.getFileName() + " is not an SPKI EC public key");
        }
    }

    private static void requireP256(ECParameterSpec params, Path file) {
        if (!params.getCurve().equals(P256.getCurve()) || !params.getGenerator().equals(P256.getGenerator())
                || !params.getOrder().equals(P256.getOrder())) {
            throw new IllegalStateException(file.getFileName() + " is not on curve P-256 (ES256 requires P-256)");
        }
    }

    private static void requirePair(PrivateKey priv, ECPublicKey pub) {
        try {
            byte[] probe = new byte[32];
            new SecureRandom().nextBytes(probe);
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initSign(priv);
            s.update(probe);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance("SHA256withECDSA");
            v.initVerify(pub);
            v.update(probe);
            if (!v.verify(sig)) {
                throw new IllegalStateException(CURRENT_KEY + " and " + CURRENT_PUB + " are not a key pair");
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot verify the current execution-jwt key pair", e);
        }
    }

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters p = AlgorithmParameters.getInstance("EC");
            p.init(new ECGenParameterSpec("secp256r1"));
            return p.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK lacks P-256", e);
        }
    }

    // ─── JWK helpers ────────────────────────────────────────────────────────

    /** Unsigned big-endian coordinate padded to 32 bytes, base64url (RFC 7518 §6.2.1.2). */
    static String coordinate(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    /** RFC 7638 thumbprint: SHA-256 over {"crv","kty","x","y"} in lexicographic order, no whitespace. */
    public static String thumbprint(ECPublicKey key) {
        String json = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + coordinate(key.getW().getAffineX())
                + "\",\"y\":\"" + coordinate(key.getW().getAffineY()) + "\"}";
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The JWKS document (both keys) served to Envoy. */
    public String jwksJson() {
        return "{\"keys\":[" + jwk(currentPublic, currentKid) + "," + jwk(previousPublic, previousKid) + "]}";
    }

    private static String jwk(ECPublicKey k, String kid) {
        return "{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"" + coordinate(k.getW().getAffineX())
                + "\",\"y\":\"" + coordinate(k.getW().getAffineY()) + "\",\"use\":\"sig\",\"alg\":\"ES256\",\"kid\":\""
                + kid + "\"}";
    }

    @Override
    public String toString() {  // never key material
        return "ExecutionJwtKeys[current kid=" + currentKid + ", previous kid=" + previousKid + "]";
    }
}
