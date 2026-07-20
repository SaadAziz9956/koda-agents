#!/usr/bin/env python3
"""Minimal MCP stdio server for Koda e2e tests.

Speaks newline-delimited JSON-RPC 2.0 on stdin/stdout and exposes one
tool: echo(message) -> "echo: <message>".
"""
import json
import sys

ECHO_TOOL = {
    "name": "echo",
    "description": "Echoes the given message back.",
    "inputSchema": {
        "type": "object",
        "properties": {"message": {"type": "string", "description": "Text to echo"}},
        "required": ["message"],
    },
}


def reply(msg_id, result):
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": msg_id, "result": result}) + "\n")
    sys.stdout.flush()


for line in sys.stdin:
    line = line.strip()
    if not line:
        continue
    try:
        msg = json.loads(line)
    except json.JSONDecodeError:
        continue

    method = msg.get("method")
    msg_id = msg.get("id")

    if method == "initialize":
        reply(msg_id, {
            "protocolVersion": msg.get("params", {}).get("protocolVersion", "2025-03-26"),
            "capabilities": {"tools": {}},
            "serverInfo": {"name": "mock-mcp", "version": "0.1.0"},
        })
    elif method == "notifications/initialized":
        pass  # notification, no response
    elif method == "ping":
        reply(msg_id, {})
    elif method == "tools/list":
        reply(msg_id, {"tools": [ECHO_TOOL]})
    elif method == "tools/call":
        params = msg.get("params", {})
        message = params.get("arguments", {}).get("message", "")
        reply(msg_id, {
            "content": [{"type": "text", "text": f"echo: {message}"}],
            "isError": False,
        })
    elif msg_id is not None:
        sys.stdout.write(json.dumps({
            "jsonrpc": "2.0", "id": msg_id,
            "error": {"code": -32601, "message": f"method not found: {method}"},
        }) + "\n")
        sys.stdout.flush()
