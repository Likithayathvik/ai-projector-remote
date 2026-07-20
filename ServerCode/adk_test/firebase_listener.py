"""
Lightweight always-on Firebase listener for the Projector Voice Agent.

Runs as a small systemd service that stays up 24/7. It holds a single
persistent streaming connection to Firebase Realtime Database — near-zero
CPU while idle, since it's just an open network stream waiting for
updates, not polling — and starts/stops voice_server.py in response to
writes from the Android app.

Flow:
    App writes  /server_control = {command: "start"|"stop", ts}
    This script sees the change, starts/stops voice_server.py
    This script writes back /server_status = {state, ts} so the app
    can show the real state instead of guessing

Setup required before running (see README section at the bottom of this
file, and firebase-listener.service):
    1. A Firebase project with Realtime Database enabled.
    2. A service account key downloaded as serviceAccountKey.json in
       this same folder (Firebase Console → Project Settings →
       Service Accounts → Generate new private key).
    3. FIREBASE_DB_URL set to your Realtime Database URL, e.g.
       https://your-project-default-rtdb.firebaseio.com
"""

import os
import socket
import signal
import subprocess
import threading
import time

import firebase_admin
from firebase_admin import credentials, db

VOICE_SERVER_SCRIPT = os.path.join(os.path.dirname(__file__), "voice_server.py")
PYTHON_BIN = os.environ.get("VOICE_SERVER_PYTHON", "python3")
LOG_FILE = os.path.join(os.path.dirname(__file__), "voice_server.log")
SERVICE_ACCOUNT_PATH = os.path.join(os.path.dirname(__file__), "serviceAccountKey.json")
FIREBASE_DB_URL = os.environ.get(
    "FIREBASE_DB_URL", "https://REPLACE-WITH-YOUR-PROJECT-default-rtdb.firebaseio.com"
)
VOICE_SERVER_PORT = 8765
READY_TIMEOUT_SECONDS = 15

_process: subprocess.Popen | None = None
_lock = threading.Lock()
_status_ref = None  # set in main()


def _is_running() -> bool:
    global _process
    return _process is not None and _process.poll() is None


def _write_status(state: str) -> None:
    if _status_ref is not None:
        try:
            _status_ref.set({"state": state, "ts": int(time.time() * 1000)})
        except Exception as e:
            print(f"⚠️  Failed to write status to Firebase: {e}")


def _wait_until_port_open(port: int, timeout: float) -> bool:
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return True
        except OSError:
            time.sleep(0.5)
    return False


def _start_voice_server() -> None:
    global _process
    with _lock:
        if _is_running():
            print(f"ℹ️  Already running (pid={_process.pid})")
            _write_status("running")
            return

        _write_status("starting")
        log_fh = open(LOG_FILE, "a")
        _process = subprocess.Popen(
            [PYTHON_BIN, VOICE_SERVER_SCRIPT],
            stdout=log_fh,
            stderr=log_fh,
            cwd=os.path.dirname(__file__),
            start_new_session=True,
        )
        print(f"🚀 Started voice_server.py (pid={_process.pid}), waiting for it to be ready ...")

    # Wait outside the lock so start/stop requests aren't blocked while we poll.
    if _wait_until_port_open(VOICE_SERVER_PORT, READY_TIMEOUT_SECONDS):
        print("✅ voice_server.py is ready")
        _write_status("running")
    else:
        print("⚠️  voice_server.py did not open its port in time — check voice_server.log")
        _write_status("running" if _is_running() else "stopped")


def _stop_voice_server() -> None:
    global _process
    with _lock:
        if not _is_running():
            print("ℹ️  Already stopped")
            _write_status("stopped")
            return

        print(f"🛑 Stopping voice_server.py (pid={_process.pid})")
        _process.send_signal(signal.SIGINT)
        try:
            _process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            _process.kill()
            _process.wait()
        _process = None
        _write_status("stopped")


def _on_control_change(event) -> None:
    data = event.data
    if not isinstance(data, dict):
        return
    command = data.get("command")
    if command == "start":
        threading.Thread(target=_start_voice_server, daemon=True).start()
    elif command == "stop":
        threading.Thread(target=_stop_voice_server, daemon=True).start()


def main() -> None:
    global _status_ref

    if not os.path.exists(SERVICE_ACCOUNT_PATH):
        raise SystemExit(
            f"❌ Missing {SERVICE_ACCOUNT_PATH} — download it from Firebase Console "
            f"→ Project Settings → Service Accounts → Generate new private key."
        )

    cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
    firebase_admin.initialize_app(cred, {"databaseURL": FIREBASE_DB_URL})

    _status_ref = db.reference("/server_status")
    _write_status("stopped")  # baseline on boot — nothing is running yet

    control_ref = db.reference("/server_control")
    print(f"👂 Listening for changes at /server_control on {FIREBASE_DB_URL} ...")
    control_ref.listen(_on_control_change)

    # listen() runs its own background thread; keep the main thread alive.
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("\n🛑 Listener stopped by user.")
        _stop_voice_server()


if __name__ == "__main__":
    main()

# ── README ───────────────────────────────────────────────────────────────
# Firebase project setup (one-time, do this in the Firebase Console):
#   1. Go to https://console.firebase.google.com → Create/select a project.
#   2. Build → Realtime Database → Create Database → start in test mode
#      (or set rules to restrict write access to your service account +
#      the app's expected auth, if you want tighter security later).
#   3. Project Settings (gear icon) → Service Accounts → Generate new
#      private key → save the downloaded JSON as serviceAccountKey.json
#      in this adk_test folder on the VM (keep it out of version control).
#   4. Copy the Realtime Database URL shown in the Realtime Database tab
#      (looks like https://<project-id>-default-rtdb.firebaseio.com) and
#      set it as FIREBASE_DB_URL (see firebase-listener.service).
#   5. Project Settings → General → Add app → Android → register with
#      applicationId "com.projector.airemote" → download google-services.json
#      → place it at AiProjectorRemote/app/google-services.json before
#      building the Android app.
