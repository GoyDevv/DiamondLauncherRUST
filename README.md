# ◆ DiamondLauncher — Android MC Rust Launcher (Horizon 26.3 × BoardBridge)

**DiamondLauncher** is an open-source Android launcher and native Rust runtime bridge for **Minecraft 26.3 (Java → Rust)**. It ports the desktop **[Horizon Launcher](https://github.com/ludovic111/horizon)** (`horizon-main/launcher`) to Android using **Material You (Material 3 Jetpack Compose)** with **zero decorative UI bloat**, running on top of **[BoardBridge](https://github.com/GoyDevv/BoardBridge)** (`libboardbridge.so`).

---

## Credits & Open-Source Attribution

- **[Horizon — Minecraft 26.3 in Rust & Horizon Launcher](https://github.com/ludovic111/horizon)** by **Ludovic Marie (`@ludovic111`)** — Licensed under the **MIT License**.
  - Clean-room Rust reimplementation of Minecraft: Java Edition 26.3 (`horizon-client`, `horizon-server`, `horizon-launcher`, `horizon-friends`, `horizon-port-pack`) and the *Blocks* editorial index design language (`docs/design-system.md`).
- **[BoardBridge](https://github.com/GoyDevv/BoardBridge)** by **GoyDevv** — Licensed under the **Apache License 2.0**.
  - Rust-based Android native runtime bridge (JNI, `ANativeWindow`, EGL 1.4 / OpenGL ES 3.2, SDL3 26.3+ & GLFW <=26.2 input translation), originally inspired by **[Boardwalk](https://github.com/zhuowei/Boardwalk)** by **zhuowei**.

### Legal & Non-Affiliation Notice

> **NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT.**
>
> *Minecraft* is a trademark of Mojang Synergies AB. Neither **DiamondLauncher**, **Horizon**, nor **BoardBridge** is affiliated with, endorsed by, or sponsored by Mojang Studios, Microsoft Corporation, or Xbox Game Studios. No proprietary Mojang code, assets, or binaries are distributed in this repository.

---

## Architecture & What Was Built

### 1. Rust Native Bridge (`boardbridge/src/horizon/` & `boardbridge/src/platform/`)
- **`horizon::auth` (`boardbridge/src/horizon/auth.rs`)**:
  - Zero-dependency RFC 1321 MD5 + RFC 4122 version-3 (`OfflinePlayer:<name>`) UUID generator matching `horizon-main/launcher/src/auth.rs` byte-for-byte (`offline_uuid("Notch") == b50ad385-829d-3141-a216-7e7d7539ba7f`).
  - Strict Minecraft username validator (`3..=16` ASCII `[A-Za-z0-9_]`), deterministic `Player{:05}` generator, and `horizon-player.txt` parser/serializer.
- **`horizon::launch` (`boardbridge/src/horizon/launch.rs`)**:
  - Pinned Minecraft **26.3** constants (`PROTOCOL_VERSION = 777`, `DATA_VERSION = 5023`, `RESOURCE_PACK_FORMAT = 97.1`, `DATA_PACK_FORMAT = 121.0`, `JAVA_VERSION = 25`, `CLIENT_JAR_SHA1 = 569103cadfc8eb6fb2ac3eae1fe0bee2bc5dc4f8`).
  - Builds CLI arguments (`--gameDir`, `--version`, `--username`, `--uuid`, `--accessToken`, `--userType`) and `HORIZON_SESSION_FILE` environment map for `horizon-client`.
- **`horizon::parity` (`boardbridge/src/horizon/parity.rs`)**:
  - Embedded 26.3 parity telemetry (14/14 subsystems at `1.00`, 329 differential parity harnesses, 104 decompiled TSV port maps).
- **`platform::sdl3` (`boardbridge/src/platform/sdl3.rs`) — Upgraded from Interface-Only to Active Translator**:
  - Full `Sdl3PlatformBackend` and `Sdl3StateTracker` translating Android `SurfaceView` touch, key, mouse, and scroll events into `Sdl3EventPacket` streams with normalized `[0.0, 1.0]` finger coordinates, relative `dx`/`dy` deltas, and `SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER`.
- **`platform::glfw_compat` (`boardbridge/src/platform/glfw_compat.rs`) — Upgraded from Interface-Only to Active State Layer**:
  - Full `GlfwCompatPlatformBackend` and `GlfwWindowState` tracking key state arrays, mouse button states, virtual cursor coordinates, and `GLFW_CURSOR_NORMAL`/`GLFW_CURSOR_DISABLED` modes for `<= 26.2` compatibility.

### 2. Android Material You (Jetpack Compose) Launcher (`app/src/main/java/com/diamondlauncher/`)
Follows Horizon's `docs/design-system.md` (*"Type is the interface. Lists, not cards. Numbered `01`..`08` index rows separated by `1dp` hairlines"):
- **01 // Play (`PlayTabScreen`)**: Launch bar for Minecraft 26.3 Rust, active Offline Account summary, isolated Instance switcher/creator (`instances.json`), and saved Worlds reader (`saves/<world>/level.dat` via `NbtCodec`).
- **02 // Mods (`ModsTabScreen`)**: Native Horizon Registry browser (`.hmod`, `.hpack`, `.hshader`, schema v1) + Modpack Porter & Rust Coverage Analyzer (`horizon-port-pack`).
- **03 // Servers (`ServersTabScreen`)**: Multiplayer server list (`servers.dat` NBT read/write) + `horizon-friends` Crockford Base32 UDP hole-punch invite code generator (`HZ-XXXX-XXXX-XXXXX`).
- **04 // Versions (`VersionsTabScreen`)**: Pinned 26.3 research-lock SHA-1 verifier, BoardBridge native backend status, and the 14-subsystem / 104-TSV parity ledger.
- **05 // Notes (`NotesTabScreen`)**: Architecture & release notes plus full open-source credits.
- **06 // Settings (`SettingsTabScreen`)**: Material You dynamic wallpaper color toggle, Obsidian Dark / Quartz Light / System theme selector, 8 WCAG AA Horizon accents, Offline Mode (`dev_offline = true` by default), and live BoardBridge JNI diagnostics.

---

## Exact Parity Report

| Layer / Scope | Metric | Exact Parity % |
|---|---|---|
| **Horizon 26.3 Differential Harness Suite** | `14 / 14` subsystems scoring `1.00` across `329` differential parity harnesses (`tests/test_parity.py`) | **100.00%** |
| **Horizon 26.3 Decompiled Method Port Ledger (Non-Skipped)** | `9,248` implemented (`6,460` `port` + `2,788` `inline`) out of `11,021` target methods (`1,773` `missing`) across `104` TSV files | **83.91%** |
| **Horizon 26.3 Total Cataloged Method Resolution** | `12,899` resolved (`9,248` implemented + `3,651` intentionally skipped) out of `14,672` total methods | **87.92%** |
| **Horizon 26.3 Resource Pack Feature Matrix** | `32 / 45` full works (`35 / 45` including partial support) in `docs/resource-packs.md` | **71.11% – 77.78%** |
| **DiamondLauncher UI & Feature Port (vs Horizon Desktop Launcher)** | `6 / 6` screens + `10 / 10` launcher subsystems (`Offline Auth`, `Skins/Capes`, `Instances`, `Worlds NBT`, `Servers NBT`, `Mods/Packs`, `Modpack Porter`, `Friends Codes`, `Log Scrubber`, `26.3 Lock`) | **100.00%** |
| **BoardBridge Runtime Subsystems** | `12 / 14` subsystems implemented (`JNI`, `Lifecycle`, `Surface`, `EGL/GLES 3.2`, `SDL3 26.3+`, `GLFW <=26.2`, `Keymap`, `Input Queue`, `Render Loop`, `Memory`, `Horizon Auth/Launch`, `Launcher UI`; external `Vulkan` & desktop `JVM` remain optional hardware/legacy seams) | **85.71%** |
