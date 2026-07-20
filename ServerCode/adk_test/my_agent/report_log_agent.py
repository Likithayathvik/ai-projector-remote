"""
ReportLog Agent — genuine ADK BaseAgent exposed as a standard A2A server.
Uses ADK's to_a2a() to auto-generate Agent Card and expose JSON-RPC endpoint.
Run with: uvicorn my_agent.report_log_agent:app --host 0.0.0.0 --port 8766

A2A endpoint: http://localhost:8766
Agent Card:   http://localhost:8766/.well-known/agent.json

Messages accepted:
  LOG:{category}  → append log entry via File MCP Server
  UPLOAD          → upload completed log to cloud storage
"""
import os
from datetime import datetime
from typing import AsyncGenerator

from google.adk.agents import BaseAgent
from google.adk.agents.invocation_context import InvocationContext
from google.adk.a2a.utils.agent_to_a2a import to_a2a
from google.adk.events import Event
from mcp import ClientSession
from mcp.client.stdio import stdio_client, StdioServerParameters

FILE_MCP_SERVER = os.path.expanduser(
    "~/voice-agent/adk_test/file_mcp_server.py"
)

# All storage decisions owned by ReportLog Agent
LOG_DIR = os.path.expanduser("~/voice-agent/adk_test/logs")
GCS_PREFIX = "sessions"

# Session state — managed entirely by ReportLog Agent
_session_id = {"value": None}
_log_file = {"value": None}


def _init_session():
    """Auto-creates session ID and log file on first LOG message."""
    session_id = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    os.makedirs(LOG_DIR, exist_ok=True)
    log_file = os.path.join(LOG_DIR, f"session_{session_id}.log")
    _session_id["value"] = session_id
    _log_file["value"] = log_file
    print(f"📋 ReportLog Agent: new session {session_id}")
    return log_file


def _get_gcs_object() -> str:
    return f"{GCS_PREFIX}/session_{_session_id['value']}.log"


class ReportLogAgent(BaseAgent):
    """
    ADK BaseAgent subclass — no LLM needed.
    Exposed as standard A2A server via to_a2a().
    Handles all logging and storage via File MCP Server.

    Messages (via A2A TextPart):
      LOG:{category}  → append log entry
      UPLOAD          → upload to cloud storage
    """

    async def _run_async_impl(
        self, ctx: InvocationContext
    ) -> AsyncGenerator[Event, None]:

        # Extract text message from A2A Message parts
        message = ""
        if ctx.user_content and ctx.user_content.parts:
            for part in ctx.user_content.parts:
                if hasattr(part, "text") and part.text:
                    message = part.text
                    break

        if not message:
            return
            yield

        server_params = StdioServerParameters(
            command="python3",
            args=[FILE_MCP_SERVER],
        )

        try:
            async with stdio_client(server_params) as (read, write):
                async with ClientSession(read, write) as session:
                    await session.initialize()

                    if message.startswith("LOG:"):
                        category = message[4:]

                        # Auto-create session on first log entry
                        if _log_file["value"] is None:
                            _init_session()

                        result = await session.call_tool(
                            "append_file",
                            arguments={
                                "file_path": _log_file["value"],
                                "content": category,
                            }
                        )
                        text = result.content[0].text if result.content else "logged"
                        print(f"📝 ReportLog Agent: {text}")

                    elif message == "UPLOAD":
                        if _log_file["value"] is None:
                            print("📋 ReportLog Agent: no log file to upload")
                            return
                            yield

                        result = await session.call_tool(
                            "upload_to_storage",
                            arguments={
                                "local_file_path": _log_file["value"],
                                "object_name": _get_gcs_object(),
                            }
                        )
                        text = result.content[0].text if result.content else "uploaded"
                        print(f"☁️  ReportLog Agent: {text}")

                        # Reset for next session
                        _session_id["value"] = None
                        _log_file["value"] = None

        except Exception as e:
            print(f"⚠️  ReportLog Agent error: {e}")

        return
        yield


# Instantiate agent
report_log_agent = ReportLogAgent(
    name="report_log_agent",
    description="Logs voice assistant query activity and uploads session logs to cloud storage."
)

# Expose as standard A2A server via ADK's to_a2a()
# Auto-generates Agent Card at /.well-known/agent.json
app = to_a2a(
    report_log_agent,
    host="localhost",
    port=8766,
    protocol="http",
)
