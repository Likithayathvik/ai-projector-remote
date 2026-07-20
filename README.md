# AI Projector Remote

A voice-controlled Android remote for a projector. Speak or type a
command in the app, and it's interpreted by a Gemini-powered agent
(Google ADK) running on a GCP VM, which sends the corresponding action to
the projector over ADB. The voice agent server only runs while the app is
actually in use — Firebase Realtime Database triggers it to start/stop
automatically, so it isn't running (and isn't costing anything) 24/7.

## Repo structure

```
App/            Android app (Kotlin, Jetpack Compose)
ServerCode/     Python voice agent server, deployed on a GCP Compute Engine VM
  adk_test/
    voice_server.py       WebSocket server; runs the Gemini agent, dispatches
                           commands to the app over ADB
    firebase_listener.py  Always-on watcher; starts/stops voice_server.py in
                           response to Firebase writes from the app
    firebase-listener.service   systemd unit to keep the listener running 24/7
    my_agent/              ReportLog agent (session logging to GCS)
Firebase/       Standalone copies of firebase_listener.py / firebase-listener.service,
                for quick reference (kept in sync manually with ServerCode/adk_test/)
```

## What the voice agent supports

Exactly four tool-calling actions:
- **Set Volume** - requires an explicit percentage (e.g. "set volume to 40")
- **Open Android Settings**
- **Open WiFi Settings**
- **Open Bluetooth Settings**

Anything else (app launches, media playback, vague requests like "increase
volume", general chat) triggers a popup: *"Tool is not supported and
Please use remote for your action"* - the agent is intentionally scoped
narrowly rather than guessing.

The on-screen physical remote (power, volume, navigation, media keys, app
shortcuts) works independently of the voice agent and doesn't require the
server to be running.

## How the auto start/stop works

1. App sends its first voice/chat query of a session -> writes `"start"` to
   Firebase Realtime Database (`/server_control`)
2. `firebase_listener.py`, running 24/7 on the VM via systemd, sees the
   change instantly and launches `voice_server.py`
3. Once `voice_server.py`'s port is open, the listener writes
   `"running"` back (`/server_status`)
4. The app, which has been watching that path, sees `"running"` and
   connects via WebSocket to send the actual command
5. When the app closes/backgrounds, it writes `"stop"` -> the listener
   kills `voice_server.py`

Full detail in the docstring at the top of `firebase_listener.py`.

## First-time setup

None of the actual secrets are in this repo (see `.gitignore`). You'll need:

**For the Android app** (`App/app/google-services.json`):
Firebase Console -> Project Settings -> General -> Your apps -> Android ->
register with `applicationId` = `com.projector.airemote` -> download the
config file -> place it at that exact path.

**For the VM** (`ServerCode/adk_test/serviceAccountKey.json`):
Firebase Console -> Project Settings -> Service Accounts -> Generate new
private key -> save the downloaded file with that exact name in that folder.

**For the VM's environment variables** - create `~/.gemini_env` on the VM
(not committed) or set these directly in `firebase-listener.service`:
```
GOOGLE_API_KEY=your-gemini-api-key
FIREBASE_DB_URL=https://your-project-default-rtdb.firebaseio.com
```

## Running the server manually (for testing, without systemd)

```bash
cd ServerCode/adk_test
source ~/voice-agent/venv/bin/activate
export GOOGLE_API_KEY="..."
python3 voice_server.py
```

## Running the listener as a permanent service

```bash
cd ServerCode/adk_test
sudo cp firebase-listener.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable firebase-listener
sudo systemctl start firebase-listener
sudo systemctl status firebase-listener
```

## Known limitations / open items

- The VM itself (`gemini-voice-agent`) runs continuously - Firebase only
  stops `voice_server.py`, not the VM. A Cloud Functions-based approach to
  also stop/start the VM on demand is designed but not yet deployed
  (blocked on a billing account issue at time of writing).
- Session log uploads to Cloud Storage (`file_mcp_server.py` /
  `report_log_agent.py`) may fail with a 403 if the GCP billing account is
  in a delinquent state - this doesn't affect voice command functionality.
