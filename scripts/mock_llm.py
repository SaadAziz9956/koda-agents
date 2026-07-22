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
        import os
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        messages = body.get("messages", [])
        # Test knob: dump the whole request (overwrites each call; last call wins).
        dump = os.environ.get("KODA_MOCK_DUMP")
        if dump:
            with open(dump, "w") as fh:
                json.dump(messages, fh, indent=1)
        has_tool_result = any(m.get("role") == "tool" for m in messages)
        stream = bool(body.get("stream"))

        import re
        user_text = " ".join(
            str(m.get("content", "")) for m in messages if m.get("role") == "user"
        )
        skill_match = re.search(r"'([\w-]+)' skill", user_text)

        is_subagent = "sub-agent" in str(messages[0].get("content", "")).lower() if messages else False

        read_path = os.environ.get("KODA_MOCK_READ_PATH")
        bash_cmd = os.environ.get("KODA_MOCK_BASH")
        memory_note = os.environ.get("KODA_MOCK_MEMORY")
        create_skill = os.environ.get("KODA_MOCK_CREATE_SKILL")

        # The background memory reviewer: return one durable fact.
        sysmsg = str(messages[0].get("content", "")) if messages and messages[0].get("role") == "system" else ""
        if "memory reviewer" in sysmsg:
            self._respond({"role": "assistant", "content": "USER: The user is testing Koda's learning loop."}, "stop", stream, body)
            return

        if not has_tool_result:
            if create_skill and not is_subagent:
                call = {"name": "skill_create", "arguments": json.dumps(
                    {"name": create_skill, "description": "a test skill", "body": "# Test\nStep 1.\n"})}
                message = {"role": "assistant", "content": None, "tool_calls": [
                    {"id": "call_1", "type": "function", "function": call}]}
                self._respond(message, "tool_calls", stream, body)
                return
            if memory_note and not is_subagent:
                call = {"name": "memory", "arguments": json.dumps({"content": memory_note, "scope": "user"})}
            elif bash_cmd and not is_subagent:
                call = {"name": "bash", "arguments": json.dumps({"command": bash_cmd})}
            elif read_path and not is_subagent:
                # Test knob: read a specific path (to exercise subtree context loading).
                call = {"name": "read", "arguments": json.dumps({"file_path": read_path})}
            elif is_subagent:
                # Subagent: do one read-only search, then (next turn) summarize.
                call = {"name": "glob", "arguments": "{\"pattern\": \"**/*.kt\"}"}
            elif "delegate" in user_text.lower():
                call = {"name": "delegate",
                        "arguments": json.dumps({"task": "find the kotlin files and count them"})}
            elif skill_match:
                call = {"name": "skill",
                        "arguments": json.dumps({"name": skill_match.group(1)})}
            elif "echo" in user_text.lower():
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

        self._respond(message, finish, stream, body)

    def _respond(self, message, finish, stream, body):
        usage = {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}
        if stream:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.end_headers()
            delta = dict(message)
            if "tool_calls" in delta:
                delta["tool_calls"] = [{**c, "index": i} for i, c in enumerate(delta["tool_calls"])]
                self.wfile.write(sse({"id": "cmpl-1", "object": "chat.completion.chunk",
                                      "created": 1700000000, "model": body.get("model", "mock-1"),
                                      "choices": [{"index": 0, "delta": delta, "finish_reason": None}]}))
            else:
                # Emit text word-by-word so surfaces can show real token-by-token streaming.
                content = delta.get("content") or ""
                import re as _re
                for i, piece in enumerate(_re.findall(r"\S+\s*", content) or [content]):
                    chunk = {"role": "assistant", "content": piece} if i == 0 else {"content": piece}
                    self.wfile.write(sse({"id": "cmpl-1", "object": "chat.completion.chunk",
                                          "created": 1700000000, "model": body.get("model", "mock-1"),
                                          "choices": [{"index": 0, "delta": chunk, "finish_reason": None}]}))
                    self.wfile.flush()
            self.wfile.write(sse({"id": "cmpl-1", "object": "chat.completion.chunk",
                                  "created": 1700000000, "model": body.get("model", "mock-1"),
                                  "choices": [{"index": 0, "delta": {}, "finish_reason": finish}],
                                  "usage": usage}))
            self.wfile.write(b"data: [DONE]\n\n")
        else:
            payload = json.dumps({
                "id": "cmpl-1", "object": "chat.completion", "created": 1700000000,
                "model": body.get("model", "mock-1"),
                "choices": [{"index": 0, "message": message, "finish_reason": finish, "logprobs": None}],
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
