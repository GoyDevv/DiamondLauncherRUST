// Copyright 2026 The DiamondLauncher, Horizon, and BoardBridge Authors
// Licensed under the Apache License, Version 2.0 / MIT.

//! Horizon 26.3 Native Client & Launcher Core bridge for Android (DiamondLauncher).
//!
//! Connects BoardBridge's Android `ANativeWindow` / EGL / Vulkan / SDL3 input queue
//! with Horizon's `horizon-client` (Minecraft: Java Edition 26.3 rewritten in Rust),
//! `horizon-prepare` resource verification, and RFC 4122 UUIDv3 offline authentication.

pub mod auth;
pub mod launch;
pub mod parity;

pub use auth::{
    generate_offline_name, is_valid_offline_name, md5, offline_uuid, uuid_string,
     uuid_undashed, OfflineProfile,
};
pub use launch::{
    HorizonLaunchConfig, PinnedVersion26_3, CLIENT_JAR_SHA1, DATA_PACK_FORMAT, DATA_VERSION,
    JAVA_VERSION, PROTOCOL_VERSION, RESOURCE_PACK_FORMAT, SERVER_JAR_SHA1, VERSION_ID,
};
pub use parity::{ParitySummary, HARNESS_COUNT, SUBSYSTEM_COUNT};

/// Re-exported design tokens from `crates/horizon-theme` (`BLOCKS_DARK`, `BLOCKS_LIGHT`, `DARK`, `LIGHT`,
/// bundled Geist / Pixelify Sans fonts, and 34 Pixelarticons SVGs).
pub use horizon_theme as theme;