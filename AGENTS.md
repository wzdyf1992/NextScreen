# AGENTS.md

Guidance for AI coding agents working on **NextScreen** (Android DLNA/UPnP Media Renderer + AirPlay Screen Mirroring & Media Receiver).

## Build & Verification

- **Toolchain requirements**: JDK 17, Android SDK (`compileSdk = 36`, `minSdk = 24`, `targetSdk = 35`), Android NDK `28.2.13676358`, CMake `3.22.1`.
- **Configure SDK**: Ensure `ANDROID_HOME` is set in the environment or `sdk.dir` is configured in untracked `local.properties`.
- **Compile check (Kotlin + JNI/CMake)**:
  - Linux / macOS: `./gradlew assembleDebug`
  - Windows PowerShell: `.\gradlew.bat assembleDebug`
- **Release APK build**: `./gradlew assembleRelease` (debug-signed for direct sideloading; outputs to `app/build/outputs/apk/release/app-release.apk`).
- **Testing note**: There is currently no automated unit/instrumentation test suite; always run `assembleDebug` (or `assembleRelease`) to verify Kotlin, resources, and 3-ABI (`arm64-v8a`, `armeabi-v7a`, `x86_64`) C/C++ builds cleanly.
- **Git hygiene**: Never commit `.cxx/`, `build/`, `.gradle/`, `local.properties`, `*.apk`, signing keys, or machine-specific paths.

## Architectural Boundaries

- **Application & Foreground Service Lifecycle**:
  - `App` (`App.instance`) owns process-wide singletons: `CastPlayer`, `AirPlayServer`, and `HistoryStore`.
  - `RendererService` (`foregroundServiceType="connectedDevice"`) owns `DlnaRenderer`, holds `WifiManager.MulticastLock` + `PARTIAL_WAKE_LOCK`, monitors LAN IPv4 changes, and restarts `DlnaRenderer` / `AirPlayServer` when network interfaces or relevant `Prefs` keys change.
- **Native AirPlay Layer (`app/src/main/cpp/`)**:
  - `CMakeLists.txt` builds `libnextscreen_airplay.so` from vendored `third_party/uxplay/lib` (`llhttp`, `playfair`, RAOP/AirPlay C core) and `third_party/libplist`, linked via Prefab against `boringssl::crypto_static`, `oboe::oboe`, and Android `mediandk`.
  - **mDNS Shim (Critical)**: Upstream `third_party/uxplay/lib/dnssd.c` is **intentionally not compiled**. `android_dnssd_shim.c` implements `dnssd.h` in memory to capture `_raop._tcp` and `_airplay._tcp` TXT records, which `native_bridge.cpp` exposes via JNI so `AirPlayServer` can advertise them through `NsdAdvertiser` (Android `NsdManager`). Editing `uxplay/lib/dnssd.c` has no effect.
  - **Vendored Code Policy**: Keep Android-specific logic in `app/src/main/cpp/*` (`native_bridge.cpp`, `android_raop_callbacks.c`, `android_dnssd_shim.c`, `audio_*`); avoid modifying `third_party/uxplay/` or `third_party/libplist/` unless fixing an upstream protocol issue.
  - **16 KB Page Size**: Preserve `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` (`app/build.gradle.kts`) and `-Wl,-z,max-page-size=16384` (`CMakeLists.txt`).
- **Protocol Coexistence & Handoff (`AirPlayServer` <-> `CastPlayer`)**:
  - **Mutual exclusion**: Starting a DLNA cast (`CastPlayer.setUri(..., fromAirPlay = false)`) triggers `AirPlayServer.onDlnaTakeover()` to disconnect active AirPlay clients. Starting AirPlay mirroring stops active `CastPlayer` playback via `stopConflictingCast()`.
  - **AirPlay Video (`/play` HLS)**: Handed off from `AirPlayServer.onVideoPlay` to `CastPlayer.setUri(..., fromAirPlay = true)`. `AirPlayServer.playbackSyncRunnable` pushes playback snapshots to `nativeUpdatePlaybackInfo` every 250 ms so native `/playback-info` requests read cached C structs without blocking on UI/ExoPlayer threads.
- **Mirroring Video & Audio Pipelines**:
  - **Persistent GL Surface (`MirrorGlPipeline` + `MirrorVideoRenderer`)**: Hardware `MediaCodec` (H.264/HEVC chosen by `DecoderSelector`) decodes into an app-owned OpenGL ES 2.0 OES `SurfaceTexture`, **not** directly into `MirrorActivity`'s `SurfaceView`. This preserves the decoder across Activity recreation/rotation/backgrounding and enables immediate `fit` / `fill` / `zoom` redraws when the sender screen is static (0 incoming frames).
  - **Native Audio (`audio_engine.cpp`)**: Decodes `PCM`, `ALAC`, `AAC-LC`, and `AAC-ELD` via `AMediaCodec` into an adaptive jitter `TimelineBuffer` rendered by `Oboe`.
- **DLNA Continuous Playback Invariant (`CastPlayer` + `dlna/`)**:
  - When an episode finishes (`Player.STATE_ENDED`) and no `SetNextAVTransportURI` (`nextMedia`) is queued, `CastPlayer` sets `ended = true` and `computeState()` **must** report UPnP `STOPPED` (not `PAUSED_PLAYBACK`). Mobile sender apps rely on observing `STOPPED` via GENA / `GetTransportInfo` to push the next episode automatically while `Prefs.speed` and `Prefs.quality` carry over.

## Threading & Platform Constraints

- **`ExoPlayer` Main-Thread Affinity**: `CastPlayer.exo` must **only** be touched on the main looper (`Looper.getMainLooper()`). Cross-thread entry points on `CastPlayer` marshal mutations via `main.post { ... }`, and DLNA HTTP/SOAP threads read `@Volatile` snapshot fields (`state`, `positionMs`, `durationMs`, `muted`, `media`) lock-free.
- **Native RAOP Pthreads**: `AirPlayCallback` methods are invoked on native UxPlay `pthread`s (auto-attached/detached via pthread TLS in `android_raop_callbacks.c`). Never touch UI views or `CastPlayer.exo` directly from `AirPlayCallback` without posting to `main`, and keep `onVideoData` non-blocking.
- **Service Network Operations**: Starting/stopping/restarting `DlnaRenderer` and `AirPlayServer` runs off the main thread on `RendererService`'s single-threaded `worker` executor; `AirPlayServer` lifecycle methods are `@Synchronized`.
- **Background Activity Starts (Android 10+)**: Before calling `startActivity` from background services/callbacks (`launchUi` / `launchMirrorUi`), always guard with `App.instance.isForeground || Build.VERSION.SDK_INT < Q || Settings.canDrawOverlays(app)` and fall back to `Notifications` when background launch is restricted.
