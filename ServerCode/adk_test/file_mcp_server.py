"""
File MCP Server — a lightweight, portable MCP server for file operations.
Runs on the GCP VM as a subprocess, exposing file read/write/append
and storage upload as MCP tools.

To switch platforms (GCS → AWS → Azure):
  ONLY change the upload_to_storage function below.
  Everything else — voice_server.py, report_log_agent.py — stays identical.

Tools exposed:
  - write_file         : creates/overwrites a file
  - append_file        : appends a timestamped line to a file
  - read_file          : reads file content
  - list_log_files     : lists all session log files
  - upload_to_storage  : uploads a local file to cloud storage (config only)
"""
import os
import asyncio
from datetime import datetime
from mcp.server.fastmcp import FastMCP

mcp = FastMCP(
    name="file-mcp-server",
    instructions="File operations MCP server with cloud storage upload support."
)

LOG_DIR = os.path.expanduser("~/voice-agent/adk_test/logs")

# ─────────────────────────────────────────────
# PLATFORM CONFIG — change ONLY this section
# when moving to a different platform
# ─────────────────────────────────────────────
STORAGE_BACKEND = "gcs"  # "gcs" | "aws" | "azure"
GCS_BUCKET = "voice-agent-logs-sathya"
GCS_MCP_ENDPOINT = "https://storage.googleapis.com/storage/mcp"
# ─────────────────────────────────────────────


@mcp.tool()
def write_file(file_path: str, content: str) -> str:
    """
    Creates or overwrites a file with the given content.

    Args:
        file_path: Full path to the file to write
        content: Content to write to the file

    Returns:
        Confirmation message
    """
    os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
    with open(file_path, "w") as f:
        f.write(content)
    return f"File written successfully: {file_path}"


@mcp.tool()
def append_file(file_path: str, content: str) -> str:
    """
    Appends a timestamped line to a file, creating it if it doesn't exist.

    Args:
        file_path: Full path to the file to append to
        content: Query category to log

    Returns:
        Confirmation message with the logged line
    """
    os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"{timestamp} | {content}\n"
    with open(file_path, "a") as f:
        f.write(line)
    return f"Appended: {line.strip()}"


@mcp.tool()
def read_file(file_path: str) -> str:
    """
    Reads and returns the content of a file.

    Args:
        file_path: Full path to the file to read

    Returns:
        File content as a string, or error message if not found
    """
    if not os.path.exists(file_path):
        return f"File not found: {file_path}"
    with open(file_path, "r") as f:
        return f.read()


@mcp.tool()
def list_log_files() -> str:
    """
    Lists all session log files in the logs directory.

    Returns:
        List of log files with sizes and timestamps
    """
    if not os.path.exists(LOG_DIR):
        return "No log directory found."
    files = []
    for f in sorted(os.listdir(LOG_DIR)):
        if f.endswith(".log"):
            full_path = os.path.join(LOG_DIR, f)
            size = os.path.getsize(full_path)
            modified = datetime.fromtimestamp(
                os.path.getmtime(full_path)
            ).strftime("%Y-%m-%d %H:%M:%S")
            files.append(f"{f} | {size} bytes | {modified}")
    return "\n".join(files) if files else "No log files found."


@mcp.tool()
async def upload_to_storage(
    local_file_path: str,
    object_name: str,
) -> str:
    """
    Uploads a local file to cloud storage.
    Storage backend is configured at the top of this file —
    change STORAGE_BACKEND to switch platforms (gcs/aws/azure).

    Args:
        local_file_path: Local file path to upload
        object_name: Target path/name in the storage bucket

    Returns:
        Confirmation message with the storage URI
    """
    if not os.path.exists(local_file_path):
        return f"File not found: {local_file_path}"

    with open(local_file_path, "r") as f:
        content = f.read()

    if not content.strip():
        return "File is empty — skipping upload"

    if STORAGE_BACKEND == "gcs":
        return await _upload_to_gcs(content, object_name)
    elif STORAGE_BACKEND == "aws":
        return await _upload_to_aws(content, object_name)
    else:
        return f"Unknown storage backend: {STORAGE_BACKEND}"


async def _upload_to_gcs(content: str, object_name: str) -> str:
    """Uploads content to GCS via the official GCS MCP Server."""
    import httpx
    import google.auth
    from google.auth.transport.requests import Request

    try:
        credentials, _ = google.auth.default(
            scopes=["https://www.googleapis.com/auth/cloud-platform"]
        )
        credentials.refresh(Request())
        token = credentials.token

        payload = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "tools/call",
            "params": {
                "name": "write_text",
                "arguments": {
                    "bucketName": GCS_BUCKET,
                    "objectName": object_name,
                    "textContent": content,
                }
            }
        }
        async with httpx.AsyncClient() as client:
            response = await client.post(
                GCS_MCP_ENDPOINT,
                json=payload,
                headers={
                    "Authorization": f"Bearer {token}",
                    "content-type": "application/json",
                    "accept": "application/json, text/event-stream",
                },
                timeout=30,
            )
            response.raise_for_status()

        return f"Uploaded: gs://{GCS_BUCKET}/{object_name}"
    except Exception as e:
        return f"GCS upload failed: {e}"


async def _upload_to_aws(content: str, object_name: str) -> str:
    """
    Placeholder for AWS S3 upload.
    Replace with boto3 S3 call when moving to AWS.
    """
    return "AWS upload not configured yet"


if __name__ == "__main__":
    print("🚀 File MCP Server starting (stdio mode)...")
    mcp.run(transport="stdio")
