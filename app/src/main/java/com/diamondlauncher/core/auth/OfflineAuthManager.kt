package com.diamondlauncher.core.auth

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Complete Android port of Horizon Launcher's `auth.rs`, `accounts.rs`, and `skin.rs`.
 *
 * Implements:
 * - RFC 4122 version-3 (MD5) offline UUID derivation from `"OfflinePlayer:<name>"`
 * - Username validation (`3..=16` ASCII `[A-Za-z0-9_]`) matching vanilla Minecraft & Horizon
 * - Deterministic/random `Player{:05}` offline name generation
 * - Multi-account store (`accounts.json`) with `dev_offline = true` enabled by default
 *   so users can play immediately without waiting for a Microsoft Azure Client ID
 * - Legacy `horizon-player.txt` read/write compatibility (`name=`, `skin=`, `model=`, `cape=`)
 * - Ephemeral `session-<hex>.json` writer (`0600` owner-only permissions) for game launch
 * - 64x64 and legacy 64x32 PNG skin header validation & 64x32cape validation
 */
enum class AccountKind(val wireName: String) {
    OFFLINE("offline"),
    MICROSOFT("microsoft");

    companion object {
        fun fromWire(value: String): AccountKind =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: OFFLINE
    }
}

enum class SkinModel(val wireName: String, val label: String) {
    CLASSIC("classic", "Classic (4px Wide Arms)"),
    SLIM("slim", "Slim / Alex (3px Arms)");

    companion object {
        fun fromWire(value: String): SkinModel =
            if (value.equals("slim", ignoreCase = true)) SLIM else CLASSIC
    }
}

data class LauncherAccount(
    val uuid: String,           // 32-char lowercase hex (undashed)
    val name: String,           // 3..16 ASCII [A-Za-z0-9_]
    val kind: AccountKind = AccountKind.OFFLINE,
    val skinPath: String = "",
    val skinModel: SkinModel = SkinModel.CLASSIC,
    val capePath: String = "",
    val addedAtEpochSec: Long = System.currentTimeMillis() / 1000L,
    val lastUsedEpochSec: Long = System.currentTimeMillis() / 1000L
) {
    val dashedUuid: String
        get() = OfflineAuthManager.formatUuidDashed(uuid)

    val isOffline: Boolean
        get() = kind == AccountKind.OFFLINE
}

data class AccountStoreState(
    val accounts: List<LauncherAccount> = emptyList(),
    val activeUuid: String? = null,
    val devOfflineEnabled: Boolean = true,
    val customMsaClientId: String = ""
) {
    val activeAccount: LauncherAccount?
        get() = accounts.firstOrNull { it.uuid == activeUuid } ?: accounts.firstOrNull()
}

data class PngDimensions(val width: Int, val height: Int)

object OfflineAuthManager {

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private val IHDR_TAG = byteArrayOf(0x49, 0x48, 0x44, 0x52)

    /**
     * Compute the standard Minecraft offline-mode UUID (RFC 4122 v3 MD5 over `"OfflinePlayer:<name>"`).
     * Exact byte-for-byte match with `horizon-main/launcher/src/auth.rs::offline_uuid`.
     */
    fun computeOfflineUuidBytes(username: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        md5.update("OfflinePlayer:".toByteArray(StandardCharsets.UTF_8))
        val digest = md5.digest(username.toByteArray(StandardCharsets.UTF_8))
        // RFC 4122 §4.3: version 3 (MD5) in bits 12..15 of time_hi_and_version
        digest[6] = ((digest[6].toInt() and 0x0F) or 0x30).toByte()
        // RFC 4122 §4.1.1: variant 1 (10xx) in the top two bits of clock_seq_hi
        digest[8] = ((digest[8].toInt() and 0x3F) or 0x80).toByte()
        return digest
    }

    fun computeOfflineUuidJvm(username: String, dashed: Boolean = true): String {
        val bytes = computeOfflineUuidBytes(username)
        val hex = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return if (dashed) formatUuidDashed(hex) else hex
    }

    fun formatUuidDashed(undashedHex: String): String {
        val clean = undashedHex.replace("-", "").lowercase()
        if (clean.length != 32) return undashedHex
        return "${clean.substring(0, 8)}-${clean.substring(8, 12)}-${clean.substring(12, 16)}-${clean.substring(16, 20)}-${clean.substring(20, 32)}"
    }

    fun formatUuidUndashed(uuid: String): String =
        uuid.replace("-", "").trim().lowercase()

    /**
     * Validate a player name against Minecraft's rules: 3..=16 ASCII characters,
     * only letters, digits, and underscores (`[A-Za-z0-9_]`).
     * Exact match with `horizon-main/launcher/src/auth.rs::valid_offline_name`.
     */
    fun isValidOfflineName(name: String): Boolean {
        val bytes = name.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size != name.length) return false
        if (bytes.size !in 3..16) return false
        return name.all { ch ->
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'
        }
    }

    /**
     * Generate a random `Player{:05}` offline username matching `auth.rs::generate_offline_name()`.
     */
    fun generateOfflineName(): String {
        val n = SecureRandom().nextInt(100_000)
        return "Player%05d".format(n)
    }

    /**
     * Create a new offline [LauncherAccount] for [rawName].
     */
    fun createOfflineAccount(
        rawName: String,
        skinPath: String = "",
        skinModel: SkinModel = SkinModel.CLASSIC,
        capePath: String = ""
    ): Result<LauncherAccount> {
        val trimmed = rawName.trim()
        if (!isValidOfflineName(trimmed)) {
            return Result.failure(
                IllegalArgumentException("Username must be 3–16 characters using only A–Z, a–z, 0–9, or _")
            )
        }
        val undashed = computeOfflineUuidJvm(trimmed, dashed = false)
        val now = System.currentTimeMillis() / 1000L
        return Result.success(
            LauncherAccount(
                uuid = undashed,
                name = trimmed,
                kind = AccountKind.OFFLINE,
                skinPath = skinPath,
                skinModel = skinModel,
                capePath = capePath,
                addedAtEpochSec = now,
                lastUsedEpochSec = now
            )
        )
    }

    /**
     * Load `accounts.json` and `horizon-player.txt` from [dataDir].
     * Ensures at least one valid Offline Account exists when `dev_offline` is enabled.
     */
    fun loadAccountStore(dataDir: File): AccountStoreState {
        dataDir.mkdirs()
        val accountsFile = File(dataDir, "accounts.json")
        val legacyFile = File(dataDir, "horizon-player.txt")

        val accounts = mutableListOf<LauncherAccount>()
        var activeUuid: String? = null
        var devOffline = true
        var customMsaClientId = ""

        if (accountsFile.isFile) {
            runCatching {
                val root = JSONObject(accountsFile.readText(StandardCharsets.UTF_8))
                activeUuid = root.optString("active", "").takeIf { it.isNotEmpty() }
                devOffline = root.optBoolean("dev_offline", true)
                customMsaClientId = root.optString("msa_client_id", "")
                val arr = root.optJSONArray("accounts") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val name = obj.optString("name", "")
                    val uuid = obj.optString("uuid", "").ifEmpty {
                        computeOfflineUuidJvm(name, dashed = false)
                    }
                    if (name.isNotEmpty()) {
                        accounts.add(
                            LauncherAccount(
                                uuid = formatUuidUndashed(uuid),
                                name = name,
                                kind = AccountKind.fromWire(obj.optString("kind", "offline")),
                                skinPath = obj.optString("skin_path", ""),
                                skinModel = SkinModel.fromWire(obj.optString("skin_model", "classic")),
                                capePath = obj.optString("cape_path", ""),
                                addedAtEpochSec = obj.optLong("added_at", System.currentTimeMillis() / 1000L),
                                lastUsedEpochSec = obj.optLong("last_used", System.currentTimeMillis() / 1000L)
                            )
                        )
                    }
                }
            }
        }

        // Check legacy horizon-player.txt if accounts is empty
        if (accounts.isEmpty() && legacyFile.isFile) {
            runCatching {
                var legacyName = ""
                var legacySkin = ""
                var legacyModel = SkinModel.CLASSIC
                var legacyCape = ""
                legacyFile.readLines(StandardCharsets.UTF_8).forEach { line ->
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith("#")) return@forEach
                    val idx = t.indexOf('=')
                    if (idx > 0) {
                        val k = t.substring(0, idx).trim()
                        val v = t.substring(idx + 1).trim()
                        when (k) {
                            "name" -> if (isValidOfflineName(v)) legacyName = v
                            "skin" -> legacySkin = v
                            "model" -> legacyModel = SkinModel.fromWire(v)
                            "cape" -> legacyCape = v
                        }
                    }
                }
                if (legacyName.isNotEmpty()) {
                    createOfflineAccount(legacyName, legacySkin, legacyModel, legacyCape).getOrNull()?.let {
                        accounts.add(it)
                        activeUuid = it.uuid
                    }
                }
            }
        }

        // Default offline account so the user can launch immediately without Azure setup
        if (accounts.isEmpty()) {
            val defaultAcc = createOfflineAccount("DiamondPlayer").getOrThrow()
            accounts.add(defaultAcc)
            activeUuid = defaultAcc.uuid
        }

        if (activeUuid == null || accounts.none { it.uuid == activeUuid }) {
            activeUuid = accounts.first().uuid
        }

        val state = AccountStoreState(
            accounts = accounts,
            activeUuid = activeUuid,
            devOfflineEnabled = devOffline,
            customMsaClientId = customMsaClientId
        )
        saveAccountStore(dataDir, state)
        return state
    }

    /**
     * Persist `accounts.json` and sync active account to `horizon-player.txt` for Horizon CLI/Client compatibility.
     */
    fun saveAccountStore(dataDir: File, state: AccountStoreState) {
        dataDir.mkdirs()
        val accountsFile = File(dataDir, "accounts.json")
        val root = JSONObject()
        root.put("version", 1)
        root.put("active", state.activeUuid ?: JSONObject.NULL)
        root.put("dev_offline", state.devOfflineEnabled)
        if (state.customMsaClientId.isNotBlank()) {
            root.put("msa_client_id", state.customMsaClientId.trim())
        }
        val arr = JSONArray()
        for (acc in state.accounts) {
            val obj = JSONObject()
            obj.put("uuid", acc.uuid)
            obj.put("name", acc.name)
            obj.put("kind", acc.kind.wireName)
            obj.put("skin_path", acc.skinPath)
            obj.put("skin_model", acc.skinModel.wireName)
            obj.put("cape_path", acc.capePath)
            obj.put("added_at", acc.addedAtEpochSec)
            obj.put("last_used", acc.lastUsedEpochSec)
            arr.put(obj)
        }
        root.put("accounts", arr)
        accountsFile.writeText(root.toString(2) + "
", StandardCharsets.UTF_8)

        // Sync active profile to horizon-player.txt (exact format from auth.rs::OfflineProfile::save)
        state.activeAccount?.let { active ->
            val legacyFile = File(dataDir, "horizon-player.txt")
            val body = buildString {
                appendLine("# Horizon offline player profile (managed by DiamondLauncher)")
                appendLine("name=${active.name}")
                appendLine("skin=${active.skinPath}")
                appendLine("model=${active.skinModel.wireName}")
                appendLine("cape=${active.capePath}")
            }
            legacyFile.writeText(body, StandardCharsets.UTF_8)
        }
    }

    /**
     * Write an ephemeral `session-<random>.json` file for `HORIZON_SESSION_FILE` during launch,
     * matching `horizon-main/launcher/src/accounts.rs::write_session_file`.
     */
    fun writeSessionFile(cacheDir: File, account: LauncherAccount, msaAccessToken: String = "0"): File {
        cacheDir.mkdirs()
        val randBytes = ByteArray(8)
        SecureRandom().nextBytes(randBytes)
        val tag = randBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val file = File(cacheDir, "session-$tag.json")
        val obj = JSONObject().apply {
            put("username", account.name)
            put("uuid", account.uuid)
            put("access_token", if (account.isOffline) "0" else msaAccessToken)
            put("user_type", if (account.isOffline) "legacy" else "msa")
            put("skin_path", account.skinPath)
            put("skin_model", account.skinModel.wireName)
            put("cape_path", account.capePath)
        }
        file.writeText(obj.toString(2), StandardCharsets.UTF_8)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        return file
    }

    /**
     * Inspect PNG header bytes to verify skin dimensions (`64x64` or legacy `64x32`)
     * or cape dimensions (`64x32`), matching `horizon-main/launcher/src/skin.rs::png_dimensions`.
     */
    fun parsePngDimensions(bytes: ByteArray): Result<PngDimensions> {
        if (bytes.size < 24) {
            return Result.failure(IllegalArgumentException("File is too small to be a PNG"))
        }
        for (i in PNG_MAGIC.indices) {
            if (bytes[i] != PNG_MAGIC[i]) {
                return Result.failure(IllegalArgumentException("Not a valid PNG file (bad signature)"))
            }
        }
        for (i in IHDR_TAG.indices) {
            if (bytes[12 + i] != IHDR_TAG[i]) {
                return Result.failure(IllegalArgumentException("Malformed PNG (missing IHDR chunk)"))
            }
        }
        val buf = ByteBuffer.wrap(bytes, 16, 8).order(ByteOrder.BIG_ENDIAN)
        val width = buf.int
        val height = buf.int
        return Result.success(PngDimensions(width, height))
    }

    fun validateSkinPng(bytes: ByteArray): Result<PngDimensions> {
        return parsePngDimensions(bytes).mapCatching { dims ->
            if ((dims.width == 64 && dims.height == 64) || (dims.width == 64 && dims.height == 32)) {
                dims
            } else {
                throw IllegalArgumentException("Skin PNG must be 64x64 or 64x32 (got ${dims.width}x${dims.height})")
            }
        }
    }

    fun validateCapePng(bytes: ByteArray): Result<PngDimensions> {
        return parsePngDimensions(bytes).mapCatching { dims ->
            if (dims.width == 64 && dims.height == 32) {
                dims
            } else {
                throw IllegalArgumentException("Cape PNG must be 64x32 (got ${dims.width}x${dims.height})")
            }
        }
    }
}