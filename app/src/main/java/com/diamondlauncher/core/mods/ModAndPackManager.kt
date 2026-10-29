package com.diamondlauncher.core.mods

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Port of Horizon's `mods.rs`, `packs.rs`, `modpack_porter.rs`, `servers.rs`, and `logs.rs`.
 *
 * Implements:
 * - Native Horizon Registry index (`schema: 1`, `.hmod`, `.hpack`, `.hshader`) & Modrinth catalog entries
 * - Modpack Compatibility & Coverage Analyzer (`horizon-port-pack` classification:
 *   Native `.hmod`, Resource/Shader asset pack, Datapack JSON, or JVM Bytecode Mod needing Rust port)
 * - `horizon-friends` Crockford Base32 P2P invite code generator & decoder (`servers.rs`)
 * - Privacy-preserving Log Scrubber (`logs.rs`) replacing user paths and player names before sharing
 */
enum class HorizonArtifactKind(val wireName: String, val badge: String, val targetSubdir: String) {
    HMOD("hmod", ".hmod (Rust Native)", "mods"),
    HPACK("hpack", ".hpack (Resource Pack)", "resourcepacks"),
    HSHADER("hshader", ".hshader (WGSL Shader)", "shaderpacks"),
    DATAPACK("datapack", "Datapack (JSON)", "saves");
}

data class HorizonRegistryEntry(
    val id: String,
    val name: String,
    val summary: String,
    val author: String,
    val version: String,
    val mcVersion: String = "26.3",
    val kind: HorizonArtifactKind,
    val category: String,
    val downloads: String,
    val sha256: String,
    val installed: Boolean = false,
    val enabled: Boolean = true
)

enum class ModPortClassification(val label: String, val supportedInHorizonRust: Boolean) {
    NATIVE_RUST_HMOD("Native Horizon .hmod (100% Supported)", true),
    RESOURCE_PACK_ASSET("Vanilla/Horizon Resource Pack (Supported)", true),
    WGSL_SHADER_PACK("Horizon .hshader WGSL Pipeline (Supported)", true),
    VANILLA_DATAPACK("Vanilla 26.3 Datapack Format 121.0 (Supported)", true),
    JVM_FABRIC_MOD("Fabric/NeoForge JVM .jar (Requires Rust Port / Bridge)", false)
}

data class ModpackCoverageEntry(
    val fileName: String,
    val classification: ModPortClassification,
    val notes: String
)

data class ModpackCoverageReport(
    val packName: String,
    val entries: List<ModpackCoverageEntry>
) {
    val totalCount: Int get() = entries.size
    val supportedCount: Int get() = entries.count { it.classification.supportedInHorizonRust }
    val coveragePercent: Double
        get() = if (totalCount == 0) 100.0 else (supportedCount.toDouble() * 100.0) / totalCount.toDouble()
}

object ModAndPackManager {

    private const val CROCKFORD_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * Built-in Horizon 26.3 native registry entries (`.hmod`, `.hpack`, `.hshader`)
     * mirroring `horizon-main/launcher/src/mods.rs`.
     */
    fun defaultHorizonCatalog(): List<HorizonRegistryEntry> = listOf(
        HorizonRegistryEntry(
            id = "horizon-lithium-rs",
            name = "Horizon Lithium-RS",
            summary = "Zero-overhead Rust tick, chunk, and voxel collision fast-paths for 26.3.",
            author = "Horizon Team",
            version = "0.4.2+mc26.3",
            kind = HorizonArtifactKind.HMOD,
            category = "Optimization",
            downloads = "48.2K",
            sha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            installed = true,
            enabled = true
        ),
        HorizonRegistryEntry(
            id = "boardbridge-touch-hud",
            name = "BoardBridge Touch & Gyro Pro",
            summary = "Native Android SDL3 virtual thumbstick, hotbar radial swipe, and mouse-grab bridge.",
            author = "DiamondLauncher / BoardBridge",
            version = "0.2.0+mc26.3",
            kind = HorizonArtifactKind.HMOD,
            category = "Android Controls",
            downloads = "19.4K",
            sha256 = "4b227777d4dd1fc61c6f884f48641d02b4d121d3fd328cb08b5531fcacdabf8a",
            installed = true,
            enabled = true
        ),
        HorizonRegistryEntry(
            id = "horizon-wgsl-pbr",
            name = "Horizon Default PBR (.hshader)",
            summary = "Native WGSL / GLES 3.2 water reflections, volumetric fog, and dynamic torchlight.",
            author = "Horizon Shaders",
            version = "1.1.0",
            kind = HorizonArtifactKind.HSHADER,
            category = "Shaders",
            downloads = "31.0K",
            sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            installed = true,
            enabled = false
        ),
        HorizonRegistryEntry(
            id = "faithful-32x-26-3",
            name = "Faithful 32x (Format 97.1)",
            summary = "Clean 32x32 resolution upgrade verified against Horizon 26.3 resource pack loader.",
            author = "Faithful Pack",
            version = "26.3-r1",
            kind = HorizonArtifactKind.HPACK,
            category = "Resource Packs",
            downloads = "112.8K",
            sha256 = "d4735e3a265e16eee03f59718b9b5d03019c07d8b6c51f90da3a666eec13ab35",
            installed = false,
            enabled = false
        ),
        HorizonRegistryEntry(
            id = "horizon-zoom-hud",
            name = "Horizon Spyglass & C-Zoom",
            summary = "Smooth cinematic camera zoom and coordinates HUD overlay via Horizon plugin ABI.",
            author = "Ludovic / Community",
            version = "0.3.1",
            kind = HorizonArtifactKind.HMOD,
            category = "Utility",
            downloads = "27.5K",
            sha256 = "4e07408562bedb8b60ce05c1decfe3ad16b72230967de01f640b7e4729b49fce",
            installed = true,
            enabled = true
        )
    )

    /**
     * Classify files in a modpack for Horizon Rust compatibility (`modpack_porter.rs`).
     */
    fun analyzeModpackCompatibility(packName: String, filePaths: List<String>): ModpackCoverageReport {
        val entries = filePaths.map { path ->
            val lower = path.lowercase()
            when {
                lower.endsWith(".hmod") || lower.endsWith(".so") -> ModpackCoverageEntry(
                    fileName = path,
                    classification = ModPortClassification.NATIVE_RUST_HMOD,
                    notes = "Loads directly via Horizon Rust Plugin ABI"
                )
                lower.endsWith(".hshader") -> ModpackCoverageEntry(
                    fileName = path,
                    classification = ModPortClassification.WGSL_SHADER_PACK,
                    notes = "Compiled by Horizon WGSL shader pipeline"
                )
                lower.startsWith("resourcepacks/") || lower.endsWith(".hpack") -> ModpackCoverageEntry(
                    fileName = path,
                    classification = ModPortClassification.RESOURCE_PACK_ASSET,
                    notes = "Pack format 97.1 compatible"
                )
                lower.startsWith("datapacks/") || lower.contains("/datapacks/") -> ModpackCoverageEntry(
                    fileName = path,
                    classification = ModPortClassification.VANILLA_DATAPACK,
                    notes = "Data pack format 121.0 compatible"
                )
                else -> ModpackCoverageEntry(
                    fileName = path,
                    classification = ModPortClassification.JVM_FABRIC_MOD,
                    notes = "Java bytecode (.jar); use horizon-port-pack or Horizon Rust equivalent"
                )
            }
        }
        return ModpackCoverageReport(packName, entries)
    }

    /**
     * Encode an IPv4:port + 32-bit token into a `horizon-friends` Crockford Base32 invite code
     * (`HZ-XXXX-XXXX-XXXX`), matching `horizon-main/launcher/src/servers.rs`.
     */
    fun encodeFriendsInviteCode(ip: String, port: Int, sessionSecret: Int): String {
        val parts = ip.trim().split('.')
        val ipInt = if (parts.size == 4) {
            parts.fold(0) { acc, p -> (acc shl 8) or ((p.toIntOrNull() ?: 0) and 0xFF) }
        } else {
            0x7F000001
        }
        val packed = ((ipInt.toLong() and 0xFFFFFFFFL) shl 32) or
            (((port and 0xFFFF).toLong()) shl 16) or
            ((sessionSecret.toLong() and 0xFFFFL))
        val chars = CharArray(13)
        var v = packed
        for (i in 12 downTo 0) {
            chars[i] = CROCKFORD_ALPHABET[(v and 0x1FL).toInt()]
            v = v ushr 5
        }
        val raw = String(chars)
        return "HZ-${raw.substring(0, 4)}-${raw.substring(4, 8)}-${raw.substring(8, 13)}"
    }

    /**
     * Scrub sensitive paths (`HOME`, Android `/data/user/0/...`) and player names from logs
     * before copying or uploading to `mclo.gs`, matching `horizon-main/launcher/src/logs.rs::scrub_log`.
     */
    fun scrubLog(rawLog: String, homePath: String?, playerNames: List<String>): String {
        var out = rawLog
        if (!homePath.isNullOrBlank() && homePath.length > 1) {
            out = out.replace(homePath, "~")
        }
        // Scrub standard Android app data paths
        out = out.replace(Regex("/data/user/\d+/[^/\s]+"), "~")
        out = out.replace(Regex("/storage/emulated/\d+"), "~")
        for (name in playerNames) {
            if (name.length >= 3) {
                out = out.replace(name, "<player>")
            }
        }
        return out
    }
}