"""A minimal MCP stdio client, for exercising the real protocol surface.

Kotlin unit tests cover the logic; this covers the thing they cannot — that the
server actually speaks MCP over stdio to a process that did not compile against
it. It caught a real bug on day one: a transitive dependency printing a banner
to stdout, which corrupts the JSON-RPC stream and kills the session with no
useful error. A test that imported the server in-process would never have seen
it.

Deliberately dependency-free: stdlib only, so it runs anywhere the project does.

    from tools.mcp_client import McpClient
    c = McpClient(["tools/host/devourer-mcp"])
    c.initialize()
    print(c.tool("radio_list", {}))
"""

import json
import subprocess
import threading
import time


class McpError(RuntimeError):
    """The server broke the protocol, rather than returning a tool error."""


class McpClient:
    def __init__(self, cmd, cwd=None, stderr_lines=200):
        self.p = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            cwd=cwd,
            bufsize=0,
        )
        self._id = 0
        self._max_err = stderr_lines
        self.err = []
        threading.Thread(target=self._drain_err, daemon=True).start()

    def _drain_err(self):
        for line in self.p.stderr:
            self.err.append(line.decode(errors="replace").rstrip())
            del self.err[: -self._max_err]

    def _tail(self, n=25):
        return "\n".join(self.err[-n:])

    def call(self, method, params=None, timeout=120):
        self._id += 1
        msg = {"jsonrpc": "2.0", "id": self._id, "method": method}
        if params is not None:
            msg["params"] = params
        self.p.stdin.write((json.dumps(msg) + "\n").encode())
        self.p.stdin.flush()

        deadline = time.time() + timeout
        while time.time() < deadline:
            line = self.p.stdout.readline()
            if not line:
                raise McpError(f"server closed stdout\nstderr:\n{self._tail()}")
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                # Anything non-JSON on stdout is fatal for a stdio transport,
                # and the cause is almost always a library writing there.
                raise McpError(
                    f"non-JSON on stdout, which breaks the MCP transport: "
                    f"{line[:200]!r}\nstderr:\n{self._tail()}"
                )
            if r.get("id") == self._id:
                return r
            # Notifications and server-initiated requests: ignore for now.
        raise McpError(f"{method} timed out after {timeout}s\nstderr:\n{self._tail()}")

    def notify(self, method, params=None):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        self.p.stdin.write((json.dumps(msg) + "\n").encode())
        self.p.stdin.flush()

    def initialize(self, protocol="2025-06-18", name="tools.mcp_client"):
        r = self.call(
            "initialize",
            {
                "protocolVersion": protocol,
                "capabilities": {},
                "clientInfo": {"name": name, "version": "1"},
            },
        )
        if "error" in r:
            raise McpError(f"initialize failed: {r['error']}")
        self.notify("notifications/initialized")
        return r["result"]

    def tools(self):
        return self.call("tools/list")["result"]["tools"]

    def tool(self, name, args, timeout=120):
        """Call a tool. Returns the parsed JSON body.

        A tool that reported an error still returns its body, with `_isError`
        set — the message is usually the interesting part (a capability refusal,
        for instance, is a correct outcome worth reading).
        """
        r = self.call("tools/call", {"name": name, "arguments": args}, timeout=timeout)
        if "error" in r:
            return {"_rpc_error": r["error"]}
        result = r["result"]
        text = "\n".join(c.get("text", "") for c in result.get("content", []))
        try:
            body = json.loads(text)
        except json.JSONDecodeError:
            return {"_text": text, "_isError": result.get("isError", False)}
        if isinstance(body, dict) and result.get("isError"):
            body["_isError"] = True
        return body

    def close(self):
        try:
            self.p.stdin.close()
        except Exception:
            pass
        try:
            self.p.wait(timeout=5)
        except Exception:
            self.p.kill()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
