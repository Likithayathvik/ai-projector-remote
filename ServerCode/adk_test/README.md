# Voice Agent — Gemini ADK + A2A + File MCP

A real-time voice assistant built with Google ADK and Gemini Live API,
running on GCP with A2A agent communication and portable File MCP logging.

## Architecture
LOCAL PC (client.py)
↕ WebSocket (port 8765)
GCP VM — voice_server.py (Main Voice Agent)
↕ Standard A2A Protocol (port 8766)
GCP VM — report_log_agent.py (ReportLog A2A Server)
↕ MCP stdio
file_mcp_server.py (File MCP Server)
↓ writes local log → uploads to GCS

## Features
- Real-time voice streaming (mic → Gemini → speaker)
- Weather queries (audio response)
- Media/volume control (text-only response)
- Swiggy food ordering via MCP
- Per-query token usage tracking
- Query logging via A2A + File MCP → GCS

## Setup

### 1. Clone the repo
```bash
git clone https://github.com/Likithayathvik/voice-agent-gemini-adk.git
cd voice-agent-gemini-adk
```

### 2. Install dependencies
```bash
pip install -r requirements.txt
```

### 3. Set up credentials
```bash
cp credentials.example.json credentials.json
cp swiggy_client_id.example.json swiggy_client_id.json
cp swiggy_token.example.json swiggy_token.json
```

### 4. Set environment variables
```bash
export GOOGLE_API_KEY="your-gemini-api-key"
export GOOGLE_GENAI_API_VERSION="v1beta"
unset GOOGLE_GENAI_USE_VERTEXAI
```

### 5. Run on GCP VM
```bash
python3 voice_server.py
```

### 6. Run on Local PC
Run client.py in Thonny
Press 1 to connect, 0 to exit

## Project Structure

```
├── voice_server.py               # Main Voice Agent + A2A client
├── file_mcp_server.py            # Portable File MCP Server
├── client.py                     # PC client (mic/speaker/hotkeys)
├── requirements.txt
├── credentials.example.json
├── swiggy_client_id.example.json
├── swiggy_token.example.json
└── my_agent/
    ├── agent.py                  # ADK agent with tools
    ├── report_log_agent.py       # ReportLog A2A Server
    ├── weather_tool.py
    ├── media_tool.py
    ├── media_commands.json
    ├── connection_registry.py
    ├── swiggy_food_tool.py
    ├── swiggy_auth.py
    └── __init__.py
```

## Notes
- Switch storage backend: change STORAGE_BACKEND in file_mcp_server.py
- Swiggy client_id obtained via Dynamic Client Registration (DCR)
- Output tokens calculated as total - input (Live API limitation)
