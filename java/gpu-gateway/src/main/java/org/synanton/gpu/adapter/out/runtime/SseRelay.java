package org.synanton.gpu.adapter.out.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.synanton.gpu.domain.model.ExecutionUsage;
import org.synanton.gpu.domain.port.out.ExecutionRuntime.StreamChunkSink;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * Shared OpenAI-compatible SSE relay for every streaming runtime (GPU-7 providers and
 * GPU-5 vLLM), so the Deployment Plan §10 guarantees are implemented once:
 * <ul>
 *   <li>every forwarded chunk carries the logical model ID (provider ID never leaks);</li>
 *   <li>{@code data: [DONE]} forwarded exactly once; duplicates suppressed;</li>
 *   <li>comments/keep-alives and non-JSON frames are not forwarded;</li>
 *   <li>usage (§10.2/§10.3): {@code include_usage} is always requested upstream so usage
 *       is authoritative; the usage chunk is forwarded only if the client asked for it.</li>
 * </ul>
 */
@Slf4j
final class SseRelay {

    static final byte[] DONE_FRAME = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);

    record Outcome(ExecutionUsage usage, boolean sawDone, boolean timedOut) {}

    /** Closes a stalled stream at its deadline (HttpClient timeouts cover headers only). */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "sse-relay-watchdog");
                t.setDaemon(true);
                return t;
            });

    private SseRelay() {}

    /** True when the client's own payload requested {@code stream_options.include_usage}. */
    static boolean clientRequestedUsage(JsonNode payload) {
        return payload.path("stream_options").path("include_usage").asBoolean(false);
    }

    /** Force streaming upstream and request authoritative usage (never drops other options). */
    static void requestStreamingWithUsage(ObjectNode payload) {
        payload.put("stream", true);
        JsonNode options = payload.get("stream_options");
        ObjectNode streamOptions = options instanceof ObjectNode o ? o : payload.putObject("stream_options");
        streamOptions.put("include_usage", true);
    }

    /**
     * Relays until {@code [DONE]} (the stream's terminal marker — providers may keep the
     * connection open afterwards) or until {@code deadline} elapses, whichever first.
     */
    static Outcome relay(Stream<String> lines, String logicalModelId, boolean forwardUsage,
                         ObjectMapper json, StreamChunkSink sink, String runtimeClass, long startMs,
                         java.time.Duration deadline) {
        java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
        var watchdog = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            lines.close(); // unblocks the pending read
        }, deadline.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            return relayUntilDone(lines, logicalModelId, forwardUsage, json, sink, runtimeClass, startMs, timedOut);
        } catch (RuntimeException e) {
            if (timedOut.get()) {
                return new Outcome(null, false, true);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
        }
    }

    private static Outcome relayUntilDone(Stream<String> lines, String logicalModelId, boolean forwardUsage,
                                          ObjectMapper json, StreamChunkSink sink, String runtimeClass,
                                          long startMs, java.util.concurrent.atomic.AtomicBoolean timedOut) {
        ExecutionUsage usage = null;
        boolean sawDone = false;
        Iterator<String> it = lines.iterator();
        while (!sawDone && it.hasNext()) {
            String line = it.next();
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if ("[DONE]".equals(data)) {
                sawDone = true;
                sink.onChunk(DONE_FRAME); // exactly once: the loop ends here
                continue;
            }
            JsonNode chunk;
            try {
                chunk = json.readTree(data);
            } catch (Exception e) {
                log.warn("runtime {} sent a non-JSON stream frame; dropped", runtimeClass);
                continue;
            }
            if (!(chunk instanceof ObjectNode object)) {
                continue;
            }
            boolean usageChunk = object.hasNonNull("usage") && object.get("usage").isObject();
            if (usageChunk) {
                JsonNode u = object.get("usage");
                usage = new ExecutionUsage(u.path("prompt_tokens").asLong(0),
                        u.path("completion_tokens").asLong(0),
                        (System.currentTimeMillis() - startMs) / 1000.0, runtimeClass);
                if (!forwardUsage) {
                    boolean noChoices = !object.has("choices") || object.get("choices").isEmpty();
                    if (noChoices) {
                        continue; // usage-only chunk the client did not ask for
                    }
                    object.remove("usage");
                }
            }
            if (object.has("model")) {
                object.put("model", logicalModelId);
            }
            try {
                sink.onChunk(("data: " + json.writeValueAsString(object) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("cannot re-serialize stream chunk", e);
            }
        }
        return new Outcome(usage, sawDone, timedOut.get());
    }
}
