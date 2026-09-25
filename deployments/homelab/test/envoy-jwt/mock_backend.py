#!/usr/bin/env python3
"""Mock TEI / vLLM backend for the T-K8S-6a local end-to-end test (stdlib only).

It stands in for tei-embedding, vllm-synthesis and vllm-reranker behind the real Envoy config
(same Service names, port 8000). Every JSON response carries "saw_authorization": true/false.
The test uses it to prove Envoy does NOT forward the execution JWT to backends
(`forward: false`).
"""
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

NAME = os.environ.get("BACKEND_NAME", "backend")


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quiet
        pass

    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path in ("/health", "/healthz"):
            return self._json(200, {"ok": True, "backend": NAME})
        self._json(404, {"error": "not found"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        req = json.loads(self.rfile.read(n) or b"{}")
        saw = self.headers.get("Authorization") is not None
        model = req.get("model", "m")
        if self.path == "/v1/embeddings":
            inputs = req.get("input", [])
            inputs = [inputs] if isinstance(inputs, str) else inputs
            return self._json(200, {"object": "list", "model": model, "saw_authorization": saw,
                                    "data": [{"object": "embedding", "index": i, "embedding": [0.1] * 768}
                                             for i, _ in enumerate(inputs)],
                                    "usage": {"prompt_tokens": 3, "total_tokens": 3}})
        if self.path in ("/v1/rerank", "/v1/score"):
            docs = req.get("documents", [])
            return self._json(200, {"model": model, "saw_authorization": saw,
                                    "results": [{"index": i, "relevance_score": 1.0 / (i + 1)} for i in range(len(docs))],
                                    "usage": {"prompt_tokens": 3, "total_tokens": 3}})
        if self.path == "/v1/chat/completions":
            if req.get("stream"):
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()
                for frame in (
                    {"id": "c1", "object": "chat.completion.chunk", "model": model, "saw_authorization": saw,
                     "choices": [{"index": 0, "delta": {"content": "OK"}}]},
                    {"id": "c1", "object": "chat.completion.chunk", "model": model, "choices": [],
                     "usage": {"prompt_tokens": 3, "completion_tokens": 1, "total_tokens": 4}},
                ):
                    self._chunk(("data: " + json.dumps(frame) + "\n\n").encode())
                self._chunk(b"data: [DONE]\n\n")
                self._chunk(b"")
                return
            return self._json(200, {"id": "c1", "object": "chat.completion", "model": model, "saw_authorization": saw,
                                    "choices": [{"index": 0, "message": {"role": "assistant", "content": "OK"},
                                                 "finish_reason": "stop"}],
                                    "usage": {"prompt_tokens": 3, "completion_tokens": 1, "total_tokens": 4}})
        self._json(404, {"error": "not found"})

    def _chunk(self, data: bytes):
        self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
        self.wfile.flush()


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
