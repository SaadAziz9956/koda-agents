#!/usr/bin/env python3
"""Mock OpenAI-compatible server for Koda e2e tests (no API key needed).

Honors the request's `stream` flag: JSON response when false/absent
(Koog's requestLLM), SSE when true. Turn logic: no tool result in the
conversation yet -> respond with a glob tool call; otherwise -> text.

Usage: python3 scripts/mock_llm.py   # listens on 127.0.0.1:8977
Then:  ./cli/build/install/cli/bin/cli --provider custom \
         --base-url http://127.0.0.1:8977 --model mock-1 --yolo
"""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = 8977


def sse(chunk):
    return f"data: {json.dumps(chunk)}\n\n".encode()


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        messages = body.get("messages", [])
        has_tool_result = any(m.get("role") == "tool" for m in messages)
        stream = bool(body.get("stream"))

        wants_echo = any(
            m.get("role") == "user" and "echo" in str(m.get("content", "")).lower()
            for m in messages
        )

        if not has_tool_result:
            if wants_echo:
                call = {"name": "echo", "arguments": "{\"message\": \"hello from koda\"}"}
            else:
                call = {"name": "glob", "arguments": "{\"pattern\": \"**/*.kts\"}"}
            message = {"role": "assistant", "content": None, "tool_calls": [
                {"id": "call_1", "type": "function", "function": call}]}
            finish = "tool_calls"
        else:
            tool_output = next(m["content"] for m in messages if m.get("role") == "tool")
            count = len([l for l in str(tool_output).splitlines() if l.strip()])
            message = {"role": "assistant",
                       "content": f"Found {count} Gradle script files in this project."}
            finish = "stop"

        usage = {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}

        if stream:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            delta = dict(message)
            if "tool_calls" in delta:
                delta["tool_calls"] = [
                    {**c, "index": i} for i, c in enumerate(delta["tool_calls"])
                ]
            self.wfile.write(sse({"id": "cmpl-1", "object": "chat.completion.chunk",
                                  "created": 1700000000, "model": body.get("model", "mock-1"),
                                  "choices": [{"index": 0, "delta": delta, "finish_reason": None}]}))
            self.wfile.write(sse({"id": "cmpl-1", "object": "chat.completion.chunk",
                                  "created": 1700000000, "model": body.get("model", "mock-1"),
                                  "choices": [{"index": 0, "delta": {}, "finish_reason": finish}],
                                  "usage": usage}))
            self.wfile.write(b"data: [DONE]\n\n")
        else:
            payload = json.dumps({
                "id": "cmpl-1", "object": "chat.completion", "created": 1700000000,
                "model": body.get("model", "mock-1"),
                "choices": [{"index": 0, "message": message, "finish_reason": finish,
                             "logprobs": None}],
                "usage": usage,
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    print(f"mock LLM listening on 127.0.0.1:{PORT}")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
