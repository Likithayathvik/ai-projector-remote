import asyncio
import json
import pyaudio
import websockets
import audioop
import keyboard

SERVER_IP   = "35.225.0.41"
SERVER_PORT = 8765

URI         = f"ws://{SERVER_IP}:{SERVER_PORT}"

SAMPLE_RATE = 16000
CHANNELS    = 1
CHUNK       = 1600
FORMAT      = pyaudio.paInt16

SILENCE_TIMEOUT = 5          # seconds of no-speech (after server finishes) before disconnecting
SILENCE_THRESHOLD = 500      # amplitude below this = considered silence
SERVER_IDLE_GAP = 1.0        # seconds of no incoming audio = server considered "done speaking"

audio = pyaudio.PyAudio()


def execute_pc_command(action: str):
    """Executes a media/volume command locally on Windows using virtual keypresses."""
    try:
        import ctypes
        VK_VOLUME_UP = 0xAF
        VK_VOLUME_DOWN = 0xAE
        VK_VOLUME_MUTE = 0xAD
        VK_MEDIA_PLAY_PAUSE = 0xB3
        VK_MEDIA_NEXT_TRACK = 0xB0
        VK_MEDIA_PREV_TRACK = 0xB1

        key_map = {
            "volume_up": VK_VOLUME_UP,
            "volume_down": VK_VOLUME_DOWN,
            "mute": VK_VOLUME_MUTE,
            "unmute": VK_VOLUME_MUTE,
            "play_pause": VK_MEDIA_PLAY_PAUSE,
            "next_track": VK_MEDIA_NEXT_TRACK,
            "previous_track": VK_MEDIA_PREV_TRACK,
        }

        vk_code = key_map.get(action)
        if vk_code is None:
            print(f"⚠️ No local handler for action: {action}")
            return

        ctypes.windll.user32.keybd_event(vk_code, 0, 0, 0)
        ctypes.windll.user32.keybd_event(vk_code, 0, 2, 0)  # key up
        print(f"🎛️  Executed local command: {action}")

    except Exception as e:
        print(f"⚠️ Failed to execute command '{action}': {e}")


def wait_for_connect_key():
    """
    Waits for the user to press '1' anywhere on the keyboard (global hotkey)
    before opening the websocket connection. Mic is NOT used during this wait.
    """
    print("⌨️  Press '1' to connect to the server...")
    keyboard.wait("1")
    print("✅ '1' pressed — connecting!")


async def main():
    # Phase 1 — wait for the global hotkey '1' (no mic, no websocket yet)
    wait_for_connect_key()

    # Phase 2 — now open the websocket connection
    print(f"🔌 Connecting to {URI} ...")
    async with websockets.connect(URI) as websocket:
        print("✅ Connected to server!")

        mic_stream = audio.open(
            format=FORMAT,
            channels=CHANNELS,
            rate=SAMPLE_RATE,
            input=True,
            frames_per_buffer=CHUNK
        )
        speaker = audio.open(
            format=FORMAT,
            channels=CHANNELS,
            rate=SAMPLE_RATE,
            output=True
        )

        print("🎤 Speak now! Press '0' to exit, or Ctrl+C to stop.")

        loop = asyncio.get_event_loop()

        last_speech_time = loop.time()
        last_server_audio_time = None
        disconnect_reason = {"silence": False, "exit": False}
        exit_triggered = {"value": False}

        async def send_audio():
            """Capture mic → send to server, track silence for watchdog."""
            nonlocal last_speech_time
            chunk_count = 0
            try:
                while True:
                    data = mic_stream.read(CHUNK, exception_on_overflow=False)
                    await websocket.send(data)

                    volume = audioop.rms(data, 2)  # 2 bytes per sample (16-bit)
                    if volume > SILENCE_THRESHOLD:
                        last_speech_time = loop.time()

                    chunk_count += 1
                    if chunk_count % 20 == 0:
                        print(f"🎤 Sent {chunk_count} audio chunks so far...")
                    await asyncio.sleep(0)
            except Exception as e:
                print(f"❌ send_audio error: {e}")
                raise

        async def receive_audio():
            """Receive from server → play on speaker, OR execute a command,
            OR print token usage info."""
            nonlocal last_server_audio_time, last_speech_time
            try:
                async for message in websocket:
                    if exit_triggered["value"]:
                        continue

                    if isinstance(message, str):
                        try:
                            payload = json.loads(message)

                            if payload.get("type") == "command":
                                action = payload.get("action")
                                print(f"🎛️  Received command: {action}")
                                execute_pc_command(action)
                                continue

                            if payload.get("type") == "token_usage":
                                print(
                                    f"\n📌 Query #{payload['query_number']} Token Usage:\n"
                                    f"   Input: {payload['input_tokens']} tokens\n"
                                    f"   Output: {payload['output_tokens']} tokens\n"
                                    f"   Total: {payload['total_tokens']} tokens\n"
                                )
                                continue

                        except json.JSONDecodeError:
                            print(f"⚠️ Unrecognized text message: {message}")
                        continue

                    print(f"🔊 Received audio: {len(message)} bytes")
                    speaker.write(message)

                    last_server_audio_time = loop.time()
                    last_speech_time = loop.time()
            except Exception as e:
                print(f"❌ receive_audio error: {e}")
                raise

        async def silence_watchdog():
            """
            Disconnect only if:
              - The server is NOT currently speaking, AND
              - The user has been silent for SILENCE_TIMEOUT sec since the server went idle
            """
            nonlocal last_speech_time, last_server_audio_time
            while True:
                await asyncio.sleep(1)
                now = loop.time()

                server_is_speaking = (
                    last_server_audio_time is not None
                    and (now - last_server_audio_time) < SERVER_IDLE_GAP
                )
                if server_is_speaking:
                    continue

                elapsed = now - last_speech_time
                if elapsed >= SILENCE_TIMEOUT:
                    disconnect_reason["silence"] = True
                    await websocket.close()
                    return

        async def exit_key_listener():
            """
            Waits for the global hotkey '0' to be pressed. Once pressed,
            immediately stops audio processing and closes the connection.
            """
            loop_local = asyncio.get_event_loop()
            await loop_local.run_in_executor(None, keyboard.wait, "0")
            exit_triggered["value"] = True
            disconnect_reason["exit"] = True
            await websocket.close()

        try:
            await asyncio.gather(
                send_audio(),
                receive_audio(),
                silence_watchdog(),
                exit_key_listener(),
            )
        except Exception:
            pass
        finally:
            if disconnect_reason["exit"]:
                print("\nExited from the server\n")
            elif disconnect_reason["silence"]:
                print("\n🔌 The Websocket connection closed as there is no voice sample received from the user\n")
            else:
                print("🔌 Connection ended.")


asyncio.run(main())