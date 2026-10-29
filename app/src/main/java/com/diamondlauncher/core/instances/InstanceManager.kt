package com.diamondlauncher.core.instances

import com.diamondlauncher.core.nbt.NbtCodec
import com.diamondlauncher.core.nbt.NbtTag
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Port of Horizon's `instances.rs` and `worlds.rs`.
 *
 * Manages isolated game directories (`instances/<slug>/` with `saves/`, `mods/`,
 * `resourcepacks/`, `shaderpacks/`, `config/`, `logs/`, `screenshots/`), per-instance
 * launch hooks, `.mrpack` / `.zip` instance exports, and `level.dat` / `servers.dat` inspection.
 */
data class InstanceLaunchConfig(
    val preLaunchCommand: String = "",
    val wrapperCommand: String = "",
    val postExitCommand: String = "",
    val extraArgs: String = "",
    val envVars: Map<String, String> = emptyMap(),
    val width: Int = 0,
    val height: Int = 0,
    val fullscreen: Boolean = true,
    val memoryMb: Int = 4096
)

data class GameInstance(
    val id: String,
    val name: String,
    val version: String = "26.3",
    val iconGlyph: String = "◆",
    val group: String = "Default",
    val notes: String = "",
    val createdEpochSec: Long = System.currentTimeMillis() / 1000L,
    val lastPlayedEpochSec: Long = 0L,
    val playTimeSeconds: Long = 0L,
    val launch: InstanceLaunchConfig = InstanceLaunchConfig()
)

data class WorldSummary(
    val folderName: String,
    val displayName: String,
    val gameMode: String,
    val versionName: String,
    val lastPlayedEpochMs: Long,
    val seed: Long,
    val hardcore: Boolean,
    val cheats: Boolean,
    val sizeBytes: Long
)

data class SavedServerEntry(
    val name: String,
    val ip: String,
    val iconBase64: String = "",
    val acceptTextures: Boolean = false,
    val hideAddress: Boolean = false
)

object InstanceManager {

    private val INSTANCE_SUBDIRS = listOf(
        "saves", "resourcepacks", "shaderpacks", "mods", "config", "screenshots", "logs"
    )

    val ICON_GLYPHS = listOf("◆", "◈", "❖", "▲", "●", "■", "✦", "★")

    fun slugify(name: String): String {
        val raw = name.trim().lowercase().map { ch ->
            if (ch in 'a'..'z' || ch in '0'..'9') ch else '-'
        }.joinToString("")
        val collapsed = raw.split('-').filter { it.isNotEmpty() }.joinToString("-")
        return collapsed.ifEmpty { "instance" }.take(40)
    }

    fun loadInstances(baseDir: File): Pair<List<GameInstance>, String> {
        baseDir.mkdirs()
        val file = File(baseDir, "instances.json")
        val list = mutableListOf<GameInstance>()
        var activeId = "default"

        if (file.isFile) {
            runCatching {
                val root = JSONObject(file.readText(StandardCharsets.UTF_8))
                activeId = root.optString("active", "default")
                val arr = root.optJSONArray("instances") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val launchObj = obj.optJSONObject("launch") ?: JSONObject()
                    val envMap = mutableMapOf<String, String>()
                    launchObj.optJSONObject("env")?.let { envJson ->
                        envJson.keys().forEach { k -> envMap[k] = envJson.optString(k, "") }
                    }
                    list.add(
                        GameInstance(
                            id = obj.optString("id", "default"),
                            name = obj.optString("name", "Default"),
                            version = obj.optString("version", "26.3"),
                            iconGlyph = obj.optString("icon", "◆"),
                            group = obj.optString("group", "Default"),
                            notes = obj.optString("notes", ""),
                            createdEpochSec = obj.optLong("created", System.currentTimeMillis() / 1000L),
                            lastPlayedEpochSec = obj.optLong("last_played", 0L),
                            playTimeSeconds = obj.optLong("play_time_seconds", 0L),
                            launch = InstanceLaunchConfig(
                                preLaunchCommand = launchObj.optString("pre_launch_command", ""),
                                wrapperCommand = launchObj.optString("wrapper_command", ""),
                                postExitCommand = launchObj.optString("post_exit_command", ""),
                                extraArgs = launchObj.optString("extra_args", ""),
                                envVars = envMap,
                                width = launchObj.optInt("width", 0),
                                height = launchObj.optInt("height", 0),
                                fullscreen = launchObj.optBoolean("fullscreen", true),
                                memoryMb = launchObj.optInt("memory_mb", 4096)
                            )
                        )
                    )
                }
            }
        }

        if (list.none { it.id == "default" }) {
            list.add(
                0,
                GameInstance(
                    id = "default",
                    name = "Horizon 26.3 Rust",
                    version = "26.3",
                    iconGlyph = "◆",
                    group = "Official Rust Port",
                    notes = "Primary Horizon 26.3 Rust client instance bridged via BoardBridge SDL3/GLES3.2."
                )
            )
        }
        if (list.none { it.id == activeId }) {
            activeId = list.first().id
        }

        list.forEach { ensureInstanceDirs(baseDir, it.id) }
        saveInstances(baseDir, list, activeId)
        return list to activeId
    }

    fun saveInstances(baseDir: File, instances: List<GameInstance>, activeId: String) {
        baseDir.mkdirs()
        val file = File(baseDir, "instances.json")
        val root = JSONObject()
        root.put("version", 1)
        root.put("active", activeId)
        val arr = JSONArray()
        for (inst in instances) {
            val obj = JSONObject()
            obj.put("id", inst.id)
            obj.put("name", inst.name)
            obj.put("version", inst.version)
            obj.put("icon", inst.iconGlyph)
            obj.put("group", inst.group)
            obj.put("notes", inst.notes)
            obj.put("created", inst.createdEpochSec)
            obj.put("last_played", inst.lastPlayedEpochSec)
            obj.put("play_time_seconds", inst.playTimeSeconds)
            val launchObj = JSONObject()
            launchObj.put("pre_launch_command", inst.launch.preLaunchCommand)
            launchObj.put("wrapper_command", inst.launch.wrapperCommand)
            launchObj.put("post_exit_command", inst.launch.postExitCommand)
            launchObj.put("extra_args", inst.launch.extraArgs)
            launchObj.put("width", inst.launch.width)
            launchObj.put("height", inst.launch.height)
            launchObj.put("fullscreen", inst.launch.fullscreen)
            launchObj.put("memory_mb", inst.launch.memoryMb)
            val envObj = JSONObject()
            inst.launch.envVars.forEach { (k, v) -> envObj.put(k, v) }
            launchObj.put("env", envObj)
            obj.put("launch", launchObj)
            arr.put(obj)
        }
        root.put("instances", arr)
        file.writeText(root.toString(2) + "
", StandardCharsets.UTF_8)
    }

    fun instanceDir(baseDir: File, instanceId: String): File {
        return if (instanceId == "default") baseDir else File(File(baseDir, "instances"), instanceId)
    }

    fun ensureInstanceDirs(baseDir: File, instanceId: String): File {
        val dir = instanceDir(baseDir, instanceId)
        dir.mkdirs()
        INSTANCE_SUBDIRS.forEach { File(dir, it).mkdirs() }
        return dir
    }

    /**
     * Read all worlds in `saves/` and parse `level.dat` via [NbtCodec], matching `worlds.rs`.
     */
    fun listWorlds(instanceRoot: File): List<WorldSummary> {
        val savesDir = File(instanceRoot, "saves")
        if (!savesDir.isDirectory) return emptyList()
        val summaries = mutableListOf<WorldSummary>()
        savesDir.listFiles()?.filter { it.isDirectory }?.forEach { worldDir ->
            val levelDat = File(worldDir, "level.dat")
            if (levelDat.isFile) {
                runCatching {
                    val root = NbtCodec.parseFile(levelDat)
                    val data = root.getCompound("Data") ?: root
                    val levelName = data.getString("LevelName") ?: worldDir.name
                    val gameType = data.getInt("GameType") ?: 0
                    val hardcore = data.getBool("hardcore") ?: false
                    val allowCommands = data.getBool("allowCommands") ?: false
                    val lastPlayed = data.getLong("LastPlayed") ?: levelDat.lastModified()
                    val seed = data.getCompound("WorldGenSettings")?.getLong("seed")
                        ?: data.getLong("RandomSeed") ?: 0L
                    val versionName = data.getCompound("Version")?.getString("Name") ?: "26.3"
                    val modeLabel = when {
                        hardcore -> "Hardcore"
                        gameType == 1 -> "Creative"
                        gameType == 2 -> "Adventure"
                        gameType == 3 -> "Spectator"
                        else -> "Survival"
                    }
                    summaries.add(
                        WorldSummary(
                            folderName = worldDir.name,
                            displayName = levelName,
                            gameMode = modeLabel,
                            versionName = versionName,
                            lastPlayedEpochMs = lastPlayed,
                            seed = seed,
                            hardcore = hardcore,
                            cheats = allowCommands,
                            sizeBytes = computeDirSize(worldDir)
                        )
                    )
                }
            }
        }
        return summaries.sortedByDescending { it.lastPlayedEpochMs }
    }

    /**
     * Create a `.zip` backup of a world folder into `backups/`, skipping `session.lock`
     * just like `horizon-main/launcher/src/worlds.rs::backup_world`.
     */
    fun backupWorld(instanceRoot: File, worldFolder: String): Result<File> = runCatching {
        val worldDir = File(File(instanceRoot, "saves"), worldFolder)
        require(worldDir.isDirectory) { "World folder not found: $worldFolder" }
        val backupsDir = File(instanceRoot, "backups").apply { mkdirs() }
        val stamp = System.currentTimeMillis() / 1000L
        val outZip = File(backupsDir, "${worldFolder}-$stamp.zip")
        ZipOutputStream(FileOutputStream(outZip)).use { zos ->
            zipDirRecursive(worldDir, worldDir, zos, skipNames = setOf("session.lock"))
        }
        outZip
    }

    /**
     * Read `servers.dat` in [instanceRoot] using [NbtCodec], matching `instances.rs::load_servers_dat`.
     */
    fun loadServersDat(instanceRoot: File): List<SavedServerEntry> {
        val file = File(instanceRoot, "servers.dat")
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = NbtCodec.parseFile(file)
            val list = root.getCompoundList("servers") ?: emptyList()
            list.map { c ->
                SavedServerEntry(
                    name = c.getString("name") ?: "Minecraft Server",
                    ip = c.getString("ip") ?: "",
                    iconBase64 = c.getString("icon") ?: "",
                    acceptTextures = c.getBool("acceptTextures") ?: false,
                    hideAddress = c.getBool("hideAddress") ?: false
                )
            }.filter { it.ip.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    /**
     * Write `servers.dat` in [instanceRoot] using [NbtCodec], matching `instances.rs::save_servers_dat`.
     */
    fun saveServersDat(instanceRoot: File, servers: List<SavedServerEntry>) {
        val items = servers.map { s ->
            val fields = mutableListOf<Pair<String, NbtTag>>(
                "name" to NbtTag.StringVal(s.name),
                "ip" to NbtTag.StringVal(s.ip),
                "hideAddress" to NbtTag.ByteVal(if (s.hideAddress) 1 else 0),
                "acceptTextures" to NbtTag.ByteVal(if (s.acceptTextures) 1 else 0)
            )
            if (s.iconBase64.isNotEmpty()) {
                fields.add("icon" to NbtTag.StringVal(s.iconBase64))
            }
            NbtTag.CompoundVal(fields)
        }
        val root = NbtTag.CompoundVal(
            listOf("servers" to NbtTag.ListVal(10, items))
        )
        val bytes = NbtCodec.encodeCompound("", root, gzip = false)
        File(instanceRoot, "servers.dat").writeBytes(bytes)
    }

    private fun computeDirSize(dir: File): Long {
        var total = 0L
        dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        return total
    }

    private fun zipDirRecursive(root: File, current: File, zos: ZipOutputStream, skipNames: Set<String>) {
        current.listFiles()?.forEach { child ->
            if (child.name in skipNames) return@forEach
            if (child.isDirectory) {
                zipDirRecursive(root, child, zos, skipNames)
            } else {
                val rel = child.relativeTo(root).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(rel))
                FileInputStream(child).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}