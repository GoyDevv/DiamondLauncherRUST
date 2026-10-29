// Copyright 2026 The DiamondLauncher, Horizon, and BoardBridge Authors
// Licensed under the Apache License, Version 2.0 / MIT.

//! Pinned Minecraft 26.3 version constants (`crates/horizon-core/src/version.rs`
//! and `research-lock.json`) and `horizon-client` launch configuration builder.

use crate::graphics::RendererKind;
use crate::horizon::auth::OfflineProfile;
use crate::platform::PlatformKind;

/// `WorldVersion.id()` — pinned Minecraft Java Edition version in Horizon.
pub const VERSION_ID: &str = "26.3";
/// `SharedConstants.getProtocolVersion()` for 26.3.
pub const PROTOCOL_VERSION: i32 = 777;
/// `WorldVersion.dataVersion()` for 26.3 saves (`level.dat`, regions, entities).
pub const DATA_VERSION: i32 = 5023;
/// `WorldVersion.packVersion(PackType.CLIENT_RESOURCES)` for 26.3.
pub const RESOURCE_PACK_FORMAT: (i64, i64) = (97, 1);
/// `WorldVersion.packVersion(PackType.SERVER_DATA)` for 26.3.
pub const DATA_PACK_FORMAT: (i64, i64) = (121, 0);
/// Pinned Java major version required by Mojang's 26.3 jars.
pub const JAVA_VERSION: u32 = 25;
/// Pinned 26.3 client.jar SHA-1 from `research-lock.json`.
pub const CLIENT_JAR_SHA1: &str = "e877b6a07acd633fb3bb475002175cec036e7b87";
/// Pinned 26.3 server.jar SHA-1 from `research-lock.json`.
pub const SERVER_JAR_SHA1: &str = "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c";

/// Pinned version descriptor.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PinnedVersion26_3;

impl PinnedVersion26_3 {
    /// Summary string for diagnostics and UI.
    pub fn summary() -> String {
        format!(
            "Minecraft {} (protocol={}, data_version={}, resource_pack={}.{}, data_pack={}.{}, java={})",
            VERSION_ID,
            PROTOCOL_VERSION,
            DATA_VERSION,
            RESOURCE_PACK_FORMAT.0,
            RESOURCE_PACK_FORMAT.1,
            DATA_PACK_FORMAT.0,
            DATA_PACK_FORMAT.1,
            JAVA_VERSION
        )
    }
}

/// Complete launch configuration for starting `horizon-client` from DiamondLauncher.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HorizonLaunchConfig {
    /// Instance home folder (`HORIZON_HOME`).
    pub game_home: String,
    /// Prepared 26.3 resources path (`--resources`).
    pub resources_dir: String,
    /// Offline player profile (`--name` + `HORIZON_OFFLINE_SKIN*`).
    pub profile: OfflineProfile,
    /// Optional world folder to open directly (`--world`).
    pub world: Option<String>,
    /// Optional server address (`--server`) or friend invite code.
    pub server: Option<String>,
    /// Render distance in chunks (`2..=64`, default `12`).
    pub render_distance: u32,
    /// Whether to run singleplayer on the integrated server thread (default `true` since #1514).
    pub server_thread: bool,
    /// Graphics backend selected in BoardBridge (`Auto`, `Gles`, `Vulkan`).
    pub renderer: RendererKind,
    /// Platform input backend (`Sdl3` for 26.3+, `GlfwCompat` for <=26.2, `AndroidNative`).
    pub platform: PlatformKind,
    /// Disable AI / MCP sockets (`HORIZON_NO_AI` / `noAi:true`).
    pub no_ai: bool,
    /// Extra CLI arguments specified by the user.
    pub extra_args: Vec<String>,
}

impl HorizonLaunchConfig {
    /// Builds the exact command-line arguments passed to `horizon-client`.
    pub fn build_cli_args(&self) -> Vec<String> {
        let mut args = vec![
            "--resources".to_string(),
            self.resources_dir.clone(),
            "--render-distance".to_string(),
            self.render_distance.clamp(2, 64).to_string(),
            "--name".to_string(),
            self.profile.name.clone(),
        ];
        if !self.server_thread {
            args.push("--in-frame".to_string());
        }
        if let Some(world) = &self.world {
            if !world.trim().is_empty() {
                args.push("--world".to_string());
                args.push(world.trim().to_string());
            }
        }
        if let Some(server) = &self.server {
            if !server.trim().is_empty() {
                args.push("--server".to_string());
                args.push(server.trim().to_string());
            }
        }
        for extra in &self.extra_args {
            if !extra.trim().is_empty() {
                args.push(extra.trim().to_string());
            }
        }
        args
    }

    /// Builds the environment variables handed to `horizon-client` and `libboardbridge.so`.
    pub fn build_env(&self) -> Vec<(String, String)> {
        let mut env = vec![
            ("HORIZON_HOME".to_string(), self.game_home.clone()),
            ("HORIZON_RESOURCES".to_string(), self.resources_dir.clone()),
            ("HORIZON_PLAYER_NAME".to_string(), self.profile.name.clone()),
            (
                "HORIZON_SERVER_THREAD".to_string(),
                if self.server_thread { "1" } else { "0" }.to_string(),
            ),
            (
                "BOARDBRIDGE_RENDERER".to_string(),
                self.renderer.name().to_string(),
            ),
            (
                "BOARDBRIDGE_PLATFORM".to_string(),
                self.platform.name().to_string(),
            ),
        ];
        if let Some(skin) = &self.profile.skin_path {
            env.push(("HORIZON_OFFLINE_SKIN".to_string(), skin.clone()));
            env.push((
                "HORIZON_OFFLINE_SKIN_MODEL".to_string(),
                self.profile.skin_model.clone(),
            ));
        }
        if let Some(cape) = &self.profile.cape_path {
            env.push(("HORIZON_OFFLINE_CAPE".to_string(), cape.clone()));
        }
        if self.no_ai {
            env.push(("HORIZON_NO_AI".to_string(), "1".to_string()));
        }
        env
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn builds_horizon_26_3_cli_and_env() {
        let mut profile = OfflineProfile::from_name("DiamondPlayer", 1);
        profile.skin_path = Some("/data/skins/diamondplayer.png".to_string());
        profile.skin_model = "slim".to_string();
        let cfg = HorizonLaunchConfig {
            game_home: "/data/horizon".to_string(),
            resources_dir: "/data/horizon/.local/horizon-client/26.3".to_string(),
            profile,
            world: Some("SurvivalWorld".to_string()),
            server: None,
            render_distance: 16,
            server_thread: true,
            renderer: RendererKind::Gles,
            platform: PlatformKind::Sdl3,
            no_ai: true,
            extra_args: vec!["--pregen".to_string()],
        };
        let args = cfg.build_cli_args();
        assert!(args.contains(&"--name".to_string()));
        assert!(args.contains(&"DiamondPlayer".to_string()));
        assert!(args.contains(&"--world".to_string()));
        assert!(args.contains(&"SurvivalWorld".to_string()));
        assert!(!args.contains(&"--in-frame".to_string()));
        let env = cfg.build_env();
        assert!(env.contains(&(
            "HORIZON_OFFLINE_SKIN_MODEL".to_string(),
            "slim".to_string()
        )));
    }
}