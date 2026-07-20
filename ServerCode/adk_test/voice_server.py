import os
os.environ.setdefault("GOOGLE_GENAI_API_VERSION", "v1beta")

import json
import uuid
import asyncio
import websockets
import traceback
import httpx
import uvicorn
from google.genai import types

from google.adk.runners import InMemoryRunner
from google.adk.agents import Agent
from my_agent.report_log_agent import app as report_log_app
from my_agent import connection_registry

from a2a.client import ClientFactory, ClientConfig
from a2a import types as a2a_types

REPORT_LOG_A2A_URL = "http://localhost:8766"

# ─── Projector Tool Functions ──────────────────────────────────────────────────

def set_volume(percent: int) -> str:
    """
    Sets the projector volume to a specific percentage (0-100). Only call
    this when the user explicitly states a target number/percentage. Do NOT
    call this for vague relative requests like "increase volume" or "turn
    it down" — those have no stated target and must use unsupported_action.
    Args:
        percent: Volume level as a percentage from 0 to 100
    Returns:
        JSON command string for the app to execute via ADB
    """
    return json.dumps({"action": "set_volume", "args": {"percent": int(percent)}})


def open_android_settings() -> str:
    """
    Opens the Android system settings screen on the projector.
    Returns:
        JSON command string for the app to execute via ADB
    """
    return json.dumps({"action": "open_settings", "args": {}})


def open_wifi_settings() -> str:
    """
    Opens the WiFi settings screen on the projector.
    Returns:
        JSON command string for the app to execute via ADB
    """
    return json.dumps({"action": "open_wifi_settings", "args": {}})


def open_bluetooth_settings() -> str:
    """
    Opens the Bluetooth settings screen on the projector.
    Returns:
        JSON command string for the app to execute via ADB
    """
    return json.dumps({"action": "open_bluetooth_settings", "args": {}})


def unsupported_action() -> str:
    """
    Call this for ANY user request that is not exactly one of: setting the
    volume to a specific percentage, opening Android settings, opening WiFi
    settings, or opening Bluetooth settings. This includes app launches
    (Netflix, Hotstar, etc.), media playback, navigation, weather, food
    ordering, general chat, or anything else — never substitute one of the
    four real tools for a request that doesn't clearly match it.
    Returns:
        JSON command string telling the app the request isn't supported
    """
    return json.dumps({"action": "unsupported", "args": {}})


# ─── Projector Agent ──────────────────────────────────────────────────────────

projector_agent = Agent(
    name="projector_agent",
    model="gemini-3.1-flash-lite-preview",
    instruction=(
        "You are a voice assistant that controls a projector via ADB commands. "
        "You have exactly 5 tools:\n"
        "1. set_volume(percent) — sets volume to an EXACT 0-100% the user "
        "explicitly stated as a number (e.g. \"set volume to 40\", \"volume "
        "50 percent\"). Never call this for vague/relative requests like "
        "\"increase volume\", \"turn it up\", \"lower the volume\", \"decrease "
        "volume\" — those have no stated percentage, so you must NOT guess "
        "one; call unsupported_action() for those instead.\n"
        "2. open_android_settings() — opens Android settings\n"
        "3. open_wifi_settings() — opens WiFi settings\n"
        "4. open_bluetooth_settings() — opens Bluetooth settings\n"
        "5. unsupported_action() — call this for ANY request that is not "
        "clearly one of the three settings actions above, or a set_volume "
        "request with an explicit percentage\n\n"
        "IMPORTANT: You MUST always call exactly one tool for every user "
        "request. Never respond with plain text. Never guess or substitute "
        "one of the four real tools for a request that doesn't clearly and "
        "specifically match it (e.g. \"open jio hotstar\", \"play music\", "
        "\"what's the weather\", \"increase volume\", \"turn it up\" are NOT "
        "valid settings/wifi/bluetooth/exact-volume requests — call "
        "unsupported_action() for these). "
        "After calling a tool, respond with a SHORT confirmation text only."
    ),
    description="Controls projector via 4 tools; declines anything else.",
    tools=[
        set_volume,
        open_android_settings,
        open_wifi_settings,
        open_bluetooth_settings,
        unsupported_action,
    ],
)

runner = InMemoryRunner(agent=projector_agent, app_name="projector_assistant")
_agent_card_cache = {"card": None}

# Readable log labels for each projector tool action
ACTION_LOG_LABELS = {
    "set_volume": "Projector Set Volume Query",
    "open_settings": "Projector Android Settings Query",
    "open_wifi_settings": "Projector WiFi Settings Query",
    "open_bluetooth_settings": "Projector Bluetooth Settings Query",
    "unsupported": "Projector Unsupported Query",
}


async def start_report_log_server():
    """Starts ReportLog A2A server as background asyncio task."""
    config = uvicorn.Config(
        app=report_log_app,
        host="0.0.0.0",
        port=8766,
        log_level="warning",
    )
    server = uvicorn.Server(config)
    print("🚀 ReportLog A2A Server starting on port 8766 ...")
    await server.serve()


async def send_to_report_log_agent(message: str):
    """Sends log entry to ReportLog Agent via standard A2A protocol."""
    try:
        async with httpx.AsyncClient() as httpx_client:
            if _agent_card_cache["card"] is None:
                response = await httpx_client.get(
                    f"{REPORT_LOG_A2A_URL}/.well-known/agent-card.json"
                )
                response.raise_for_status()
                _agent_card_cache["card"] = a2a_types.AgentCard.model_validate(
                    response.json()
                )
            config = ClientConfig(httpx_client=httpx_client)
            factory = ClientFactory(config=config)
            client = factory.create(card=_agent_card_cache["card"])
            msg = a2a_types.Message(
                role="user",
                message_id=str(uuid.uuid4()),
                parts=[a2a_types.Part(root=a2a_types.TextPart(text=message))],
            )
            async for _ in client.send_message(msg):
                pass
    except Exception as e:
        print(f"⚠️  A2A call failed: {e}")


async def process_text_query(session_id: str, user_text: str, websocket):
    """
    Processes a text query from the Android app.

    ADK fires 3 events when a tool is called:
      EVENT 1: function_call     → Gemini decided to call the tool
      EVENT 2: function_response → tool ran, result is here ← we read this
      EVENT 3: text              → agent's confirmation message

    We detect EVENT 2 to extract the JSON command and send it to the app.
    """
    print(f"📱 Processing: {user_text}")
    command_sent = False

    try:
        async for event in runner.run_async(
            user_id="app_user",
            session_id=session_id,
            new_message=types.Content(
                role="user",
                parts=[types.Part(text=user_text)]
            ),
        ):
            if event.content and event.content.parts:
                for part in event.content.parts:

                    # EVENT 2: function_response — contains our tool result JSON
                    if part.function_response:
                        try:
                            result_str = part.function_response.response.get("result", "")
                            tool_result = json.loads(result_str)
                            if "action" in tool_result:
                                # Send command to Android app
                                command_payload = {
                                    "type": "command",
                                    "action": tool_result["action"],
                                    "args": tool_result.get("args", {}),
                                }
                                await websocket.send(json.dumps(command_payload))
                                print(f"📤 Command sent: {tool_result['action']}")
                                command_sent = True

                                # Log with readable label
                                action_label = ACTION_LOG_LABELS.get(
                                    tool_result["action"],
                                    "Projector Command Query"
                                )
                                asyncio.create_task(
                                    send_to_report_log_agent(f"LOG:{action_label}")
                                )
                        except Exception as e:
                            print(f"⚠️  Error parsing tool result: {e}")

                    # EVENT 3: text response — agent's confirmation message
                    if part.text and not command_sent:
                        text_payload = {
                            "type": "text_response",
                            "message": part.text,
                        }
                        await websocket.send(json.dumps(text_payload))
                        print(f"💬 Text response: {part.text[:50]}")

            # Log function calls for debugging
            function_calls = event.get_function_calls() if hasattr(event, "get_function_calls") else []
            if function_calls:
                for fc in function_calls:
                    print(f"🔧 Tool called: {fc.name} args={fc.args}")

    except Exception as e:
        print(f"⚠️  Error processing query: {e}")
        traceback.print_exc()
        await websocket.send(json.dumps({
            "type": "error",
            "message": f"Error: {e}",
        }))


async def handle_client(websocket):
    print(f"✅ App connected: {websocket.remote_address}")
    session = None

    try:
        session = await runner.session_service.create_session(
            app_name="projector_assistant",
            user_id="app_user",
        )
        connection_registry.ACTIVE_CONNECTIONS[session.id] = websocket

        async def receive_from_app():
            """Receive text/speech messages from Android app."""
            try:
                async for message in websocket:
                    if isinstance(message, bytes):
                        continue
                    try:
                        payload = json.loads(message)
                        if payload.get("type") == "text":
                            user_text = payload.get("message", "")
                            await process_text_query(session.id, user_text, websocket)
                    except json.JSONDecodeError:
                        print(f"⚠️  Invalid JSON: {message}")
            except websockets.exceptions.ConnectionClosed:
                print("ℹ️  App disconnected")
                return

        await receive_from_app()

    except Exception as e:
        print(f"❌ ERROR: {e}")
        traceback.print_exc()

    finally:
        print(f"🔻 Cleaning up for {websocket.remote_address}")
        if session is not None:
            connection_registry.ACTIVE_CONNECTIONS.pop(session.id, None)
        print(f"❌ App disconnected: {websocket.remote_address}")
        await send_to_report_log_agent("UPLOAD")


async def main():
    print("🚀 Projector Voice Agent starting on port 8765 ...")

    # Start ReportLog A2A server in background — same event loop, one terminal
    asyncio.create_task(start_report_log_server())
    await asyncio.sleep(1)
    print("✅ ReportLog A2A Server ready on port 8766")

    async with websockets.serve(handle_client, "0.0.0.0", 8765):
        print("✅ Waiting for Android app to connect ...")
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\n🛑 Server stopped by user (Ctrl+C).")
