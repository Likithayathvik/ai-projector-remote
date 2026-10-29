# AI Projector Remote

Phone remote (CineHead) and projector agent (CineAgent) for boAt CineHead projectors.

| Build | Folder | Version | Works on | Install |
|---|---|---|---|---|
| **CineHead** (phone app, "AI Projector Remote") | repo root (`app/`) | 1.5 | **all projectors** | install the APK on the phone |
| **CineAgent normal** | `CineAgent/` | 1.1.8 | **all projectors** (E1, E1 Pro, F1, F1 Pro, others) | `install/install-projector.bat` |
| **CineAgent E1 signed** | `CineAgent-E1/` | 1.1.8 | **E1 / E1 Pro only** | one `adb install` |

There is **one phone app for every projector**. Only the projector app comes in two builds:

- **Normal** - debug-signed. The installer opens it once and grants its permissions over adb
  (usage access, notification listener, WRITE_SECURE_SETTINGS, accessibility). Works everywhere.
- **E1 signed** - signed with the E1 firmware's platform key, so Android grants its permissions at
  install and it starts by itself. That key belongs to the E1 firmware, so this build is for the
  **E1 / E1 Pro only**. On the E1 either build works; the signed one is simpler.

CineHead 1.5 needs CineAgent 1.1.8 (secure key connection): update both together.

## Install

**Normal CineAgent (any projector)** - unzip `CineAgent-1.1.8-install.zip`, double-click
`install-projector.bat`, enter the projector's IP. For Wireless debugging (e.g. F1 on Android 14)
answer **Y** to pair and type the IP:port and 6-digit code shown on the projector.

**E1 signed CineAgent (E1 / E1 Pro)**

```
adb connect <projector-ip>:5555
adb install -r CineAgent-1.1.8-E1-platform-signed.apk
```

Do not open the app; it starts and sets itself up.

**Phone** - install `CineHead-1.5-E1-keyauth.apk`, open it and tap Refresh.

Switching a projector between the two CineAgent builds needs an uninstall first (different
signatures; this also clears the phones' keys - they get new ones on the next Refresh). After
installing the E1 signed build over a removed normal build, restart the projector once.

## CI/CD

`.github/workflows/build.yml` builds on every push and pull request (Actions -> run -> Artifacts):

| Artifact | From |
|---|---|
| `CineHead-<version>-E1-keyauth.apk` | repo root |
| `CineAgent-<version>-debug-signed.apk` and `CineAgent-<version>-install.zip` (.bat + APK + adb) | `CineAgent/` + `install/` |
| `CineAgent-<version>-E1-platform-signed.apk` | `CineAgent-E1/`, signed with the E1 key |

The E1 platform key is **never** committed (`keys/`, `*.pk8`, `*.pem` are git-ignored). CI reads it
from two repository secrets, base64-encoded; without them only the E1 job fails:

| Secret | Value |
|---|---|
| `E1PRO_PLATFORM_PK8` | base64 of `platform.pk8` |
| `E1PRO_PLATFORM_X509_PEM` | base64 of `platform.x509.pem` |

Pushing a tag such as `release-1.5-1.1.8` publishes a GitHub Release with all three.
