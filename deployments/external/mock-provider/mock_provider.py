#!/usr/bin/env python3
"""OpenAI-compatible mock provider for GPU-7 acceptance (T-K8S-51, spec §37).

Implements just enough of the provider surface for the Gateway adapter to
normalize: /v1/models, /v1/chat/completions (incl. SSE streaming with terminal
usage), /v1/embeddings, /v1/rerank, /v1/responses (incl. SSE event types per
§10.4), /healthz.

Behavior knobs (env):
  MOCK_API_KEY            required bearer key (proves the credential path, §29)
  MOCK_PORT               listen port (default 8080)
  MOCK_FAIL_RATE          0..1 fraction of requests that fail with HTTP 500
  MOCK_LATENCY_MS         artificial upstream latency before every response
  MOCK_RERANK_SUPPORTED   "0" -> /v1/rerank returns 400 (§40 capability gap)
  MOCK_STRICT_MODELS      "1" (default) -> unknown model IDs return 404, as a real
                          provider would. The Gateway must send PROVIDER model IDs
                          (mock-*-1), so a SUCCESS through the Gateway proves the
                          logical -> provider rewrite (PR #15 P1.1).

Always: reports authoritative usage, echoes x-request-id back (§35 provider
request-ID preservation), never logs request bodies (prompts).
"""
import json
import os
import random
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

API_KEY = os.environ.get("MOCK_API_KEY", "")
PORT = int(os.environ.get("MOCK_PORT", "8080"))
FAIL_RATE = float(os.environ.get("MOCK_FAIL_RATE", "0"))
LATENCY_MS = int(os.environ.get("MOCK_LATENCY_MS", "0"))
RERANK_SUPPORTED = os.environ.get("MOCK_RERANK_SUPPORTED", "1") == "1"
STRICT_MODELS = os.environ.get("MOCK_STRICT_MODELS", "1") == "1"

MODELS = ["mock-chat-1", "mock-embedding-1", "mock-reranker-1"]


def usage(prompt: int, completion: int = 0):
    return {"prompt_tokens": prompt, "completion_tokens": completion,
            "total_tokens": prompt + completion}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    # quiet, content-free logging (§20: no prompts/completions in logs)
    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args))

    def _body(self):
        n = int(self.headers.get("Content-Length", 0))
        return json.loads(self.rfile.read(n) or b"{}")

    def _send(self, code, obj, rid):
        data = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("x-request-id", rid)  # preserve/echo request id
        self.end_headers()
        self.wfile.write(data)

    def _sse(self, events, rid):
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("x-request-id", rid)
        self.send_header("Connection", "close")  # SSE has no Content-Length: end by closing
        self.close_connection = True
        self.end_headers()
        for name, payload in events:
            if name:
                self.wfile.write(f"event: {name}\n".encode())
            self.wfile.write(f"data: {json.dumps(payload)}\n\n".encode())
            self.wfile.flush()
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()

    def _authorized(self, rid):
        if not API_KEY:
            return True
        if self.headers.get("Authorization") == f"Bearer {API_KEY}":
            return True
        self._send(401, {"error": {"message": "invalid key",
                                   "type": "authentication_error",
                                   "param": None, "code": "invalid_api_key"}}, rid)
        return False

    def _maybe_fail(self, rid):
        if LATENCY_MS:
            time.sleep(LATENCY_MS / 1000)
        if random.random() < FAIL_RATE:
            self._send(500, {"error": {"message": "mock upstream failure",
                                       "type": "server_error",
                                       "param": None, "code": "mock_failure"}}, rid)
            return True
        return False

    def do_GET(self):
        rid = self.headers.get("x-request-id") or str(uuid.uuid4())
        if self.path == "/healthz":
            return self._send(200, {"status": "ok"}, rid)
        if not self._authorized(rid) or self._maybe_fail(rid):
            return
        if self.path == "/v1/models":
            return self._send(200, {"object": "list", "data": [
                {"id": m, "object": "model", "created": 1686935002,
                 "owned_by": "mock"} for m in MODELS]}, rid)
        self._send(404, {"error": {"message": "not found", "type": "invalid_request_error",
                                   "param": None, "code": "not_found"}}, rid)

    def do_POST(self):
        rid = self.headers.get("x-request-id") or str(uuid.uuid4())
        if not self._authorized(rid) or self._maybe_fail(rid):
            return
        try:
            body = self._body()
        except Exception:
            return self._send(400, {"error": {"message": "invalid json",
                                              "type": "invalid_request_error",
                                              "param": None, "code": "invalid_json"}}, rid)
        handler = {
            "/v1/chat/completions": self._chat,
            "/v1/embeddings": self._embed,
            "/v1/rerank": self._rerank,
            "/v1/responses": self._responses,
        }.get(self.path)
        if not handler:
            return self._send(404, {"error": {"message": "not found",
                                              "type": "invalid_request_error",
                                              "param": None, "code": "not_found"}}, rid)
        if STRICT_MODELS and body.get("model") not in MODELS:
            return self._send(404, {"error": {"message": "model not served by this provider",
                                              "type": "invalid_request_error",
                                              "param": "model", "code": "model_not_found"}}, rid)
        handler(body, rid)

    def _chat(self, body, rid):
        prompt_tokens = max(1, sum(len(str(m.get("content", ""))) // 4
                                   for m in body.get("messages", [])))
        if body.get("stream"):
            chunk = {"id": "chatcmpl-mock", "object": "chat.completion.chunk",
                     "created": int(time.time()), "model": body.get("model", "mock-chat-1"),
                     "choices": [{"index": 0, "delta": {"content": "mock reply"},
                                  "finish_reason": None}]}
            events = [(None, chunk)]
            if body.get("stream_options", {}).get("include_usage"):
                final = dict(chunk)
                final["choices"] = [{"index": 0, "delta": {}, "finish_reason": "stop"}]
                events.append((None, final))
                events.append((None, {"id": "chatcmpl-mock",
                                      "object": "chat.completion.chunk",
                                      "created": int(time.time()),
                                      "model": body.get("model", "mock-chat-1"),
                                      "choices": [],
                                      "usage": usage(prompt_tokens, 2)}))
            return self._sse(events, rid)
        self._send(200, {"id": "chatcmpl-mock", "object": "chat.completion",
                         "created": int(time.time()),
                         "model": body.get("model", "mock-chat-1"),
                         "choices": [{"index": 0, "finish_reason": "stop",
                                      "message": {"role": "assistant",
                                                  "content": "mock reply"}}],
                         "usage": usage(prompt_tokens, 2)}, rid)

    def _embed(self, body, rid):
        inputs = body.get("input", [])
        if isinstance(inputs, str):
            inputs = [inputs]
        data = [{"object": "embedding", "index": i,
                 "embedding": [0.01] * 1536} for i, _ in enumerate(inputs)]
        self._send(200, {"object": "list", "data": data,
                         "model": body.get("model", "mock-embedding-1"),
                         "usage": usage(max(1, sum(len(x) // 4 for x in inputs)))}, rid)

    def _rerank(self, body, rid):
        if not RERANK_SUPPORTED:
            # §40: capability gap must surface deterministically, never convert silently
            return self._send(400, {"error": {"message": "rerank not supported by this model",
                                              "type": "invalid_request_error",
                                              "param": None,
                                              "code": "capability_not_supported"}}, rid)
        docs = body.get("documents", [])
        results = [{"index": i, "relevance_score": round(1.0 - i * 0.1, 3),
                    "document": {"text": d}} for i, d in enumerate(docs)]
        self._send(200, {"id": f"rerank-{uuid.uuid4()}",
                         "model": body.get("model", "mock-reranker-1"),
                         "results": results,
                         "usage": {"total_tokens": sum(len(d) // 4 for d in docs) or 1}}, rid)

    def _responses(self, body, rid):
        resp_id = f"resp-{uuid.uuid4()}"
        completed = {"type": "response.completed",
                     "response": {"id": resp_id, "object": "response",
                                  "status": "completed",
                                  "model": body.get("model", "mock-chat-1"),
                                  "output": [],
                                  "usage": usage(3, 2)}}
        if body.get("stream"):
            # §10.4: typed SSE events, never a bare [DONE] as the protocol
            return self._sse([("response.created", {"type": "response.created",
                                                    "response": {"id": resp_id}}),
                              ("response.completed", completed)], rid)
        self._send(200, completed["response"], rid)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
