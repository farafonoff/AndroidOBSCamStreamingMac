# Architecture Decision Log

Key technical decisions made during development, with rationale.

---

## 1. USB (ADB forward) over Wi-Fi

**Decision:** Stream over USB via `adb forward tcp:LOCAL tcp:8080`, not Wi-Fi.

**Why:** Wi-Fi webcam apps (DroidCam, EpocCam) have ~1 s latency because H.264 requires jitter buffers for reliability. USB is a deterministic link — no jitter buffer needed. ADB forward latency is ~5 ms.

---

## 2. MJPEG over H.264

**Decision:** Serve raw MJPEG (`multipart/x-mixed-replace`) instead of H.264.

**Why:** H.264 has inter-frame dependencies (B-frames, GOP) that force the decoder to buffer multiple frames before displaying any. MJPEG is intra-frame only — each JPEG is independent, latency is exactly one frame. At 30 fps the total budget is ~55 ms glass-to-glass.

---

## 3. Camera1 API over Camera2

**Decision:** Use the deprecated `android.hardware.Camera` (Camera1) API.

**Why:** Target device is a Huawei FIG-LX1 (P Smart 2017, Kirin 659). Its Camera2 `INFO_SUPPORTED_HARDWARE_LEVEL` is `LIMITED`. Testing confirmed that Camera2 JPEG output via `setRepeatingRequest` is throttled well below 30 fps on this HAL — jerkier and higher latency than Camera1 + software JPEG. Camera1 runs smoothly at 30 fps.

Camera2 support is kept in the codebase (`Camera2Source`, port 8081) and can be enabled via `PREFER_CAMERA2 = True` in `receiver.py` for devices with FULL hardware level.

---

## 4. Raw ServerSocket over NanoHTTPD

**Decision:** Replaced NanoHTTPD with a hand-written `ServerSocket`-based MJPEG server.

**Why:** NanoHTTPD serves the MJPEG stream via `PipedInputStream`/`PipedOutputStream`. When the Mac receiver's TCP buffer fills (due to Python's GIL or frame decoding), TCP backpressure propagates: NanoHTTPD's socket write blocks → it stops draining the pipe → `PipedOutputStream.write()` in the camera callback blocks → the camera thread freezes → NanoHTTPD times out and kills the connection every ~5 seconds.

The replacement gives each client its own writer thread and an `ArrayBlockingQueue(2)` with drop-oldest semantics. `pushFrame()` is non-blocking — the camera callback never waits on network I/O.

---

## 5. Dynamic local port above 32000

**Decision:** The Mac-side port for `adb forward` is chosen dynamically at startup above 32000 (`_find_free_port(32000)`). The phone-side port stays fixed at 8080.

**Why:** Port 8080 is commonly used by local dev servers, causing `adb forward` to fail silently. Ports above 32000 are rarely in use. The phone's port 8080 is safe because it is only accessible via ADB (phone's localhost) — no Mac-side conflict is possible.

---

## 6. pyvirtualcam + OBS Virtual Camera over CoreMediaIO Extension

**Decision:** Write frames to OBS Virtual Camera via `pyvirtualcam` instead of building a native CoreMediaIO extension.

**Why:** A CoreMediaIO Extension requires Xcode, an Apple Developer account ($99/year), system extension signing, and a host app for installation. `pyvirtualcam` on macOS writes directly to the OBS Virtual Camera driver (a CMIOExtension installed by OBS), which appears system-wide in all apps. OBS is a project dependency anyway.

---

## 7. Camera open/close detection via OBS extension log

**Decision:** Detect when any app opens the virtual camera by streaming macOS unified logs from the OBS camera extension process and watching for two specific method names:

- `_clientQueue_addStreamingClient` → camera opened
- `_clientQueue_removeStreamingClient` → camera closed (with 2 s debounce)

**Why:** `AVCaptureDevice.isInUseByAnotherApplication` (the obvious API) returns `False` for CMIOExtension-based virtual cameras — it only works for physical cameras. Process list diffs don't work for always-running apps like Teams. The OBS extension is the ground truth: CoreMediaIO calls these exact methods when consumer apps connect and disconnect.

---

## 8. Phone camera power saving via client count

**Decision:** `CameraService` opens the physical camera only when at least one client is connected to `MjpegServer`, and closes it when the last client disconnects.

**Why:** An open camera keeps the ISP and sensor powered, generating significant heat. When no call is active the receiver holds no TCP connection to the phone, so the camera stays off. This is the primary mechanism for reducing phone temperature during idle periods.

---

## 9. Reconnection loop inside `phone_stream()`

**Decision:** `phone_stream()` loops internally until the camera closes, handling USB disconnects and reconnects without returning to the main loop.

**Why:** The original design returned from `phone_stream()` on any connection failure, causing the main loop to call it again immediately — a tight spin with no logging and no delay, resulting in silent black frames after USB reconnect. The internal loop retries with a 1 s back-off, logs each state transition, and correctly distinguishes "ADB not ready" from "ADB ready but phone app not responding".

---

## 10. Auto-start phone app via ADB

**Decision:** Before each connection attempt, `ensure_phone_app()` checks `dumpsys activity services` and fires `am start-foreground-service` if `CameraService` is not running.

**Why:** Android can kill the foreground service (battery optimization, app swipe). Requiring the user to manually open the phone app after USB reconnect breaks the headless workflow. ADB shell commands are available whenever the phone is connected, so the receiver can self-heal without user intervention.

---

## 11. Camera settings stored on the phone (SharedPreferences)

**Decision:** EV compensation and focus mode are stored in `SharedPreferences` on the phone and controlled via the app UI. The receiver connects with a plain URL — no query params.

**Why:** The phone is the entity that actually uses these settings and the user tunes them by looking at the camera output. Storing them in `receiver.py` would require editing a file and restarting a daemon on the Mac every time. SharedPreferences persist across app restarts and phone reboots, making the settings truly "set and forget". The receiver is correctly reduced to a pure transport layer.

---

## 12. Two-port architecture for Camera API selection

**Decision:** `CameraService` runs two `MjpegServer` instances — Camera1 on port 8080, Camera2 on port 8081. The receiver connects to one port based on `PREFER_CAMERA2`.

**Why:** Allows switching camera API without modifying the Android app — just change `PREFER_CAMERA2` and restart the Mac daemon. Only one camera source is active at a time (enforced by a shared `cameraExecutor`). The unused server stays in `acceptLoop()` with negligible overhead.
