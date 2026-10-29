package com.diamondlauncher.core.download

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Complete Android port of Horizon's `mojang.rs`, `minecraft_versions.rs`, `sources.rs`,
 * and `crates/horizon-prepare/src/{lib,artifacts,sounds}.rs`.
 *
 * Supports 4 ways to acquire and prepare Minecraft 26.3 directly on an Android phone (e.g. Vivo Y27):
 * 1. Official Mojang CDN (`piston-meta.mojang.com`, `piston-data.mojang.com`, `resources.download.minecraft.net`)
 * 2. Fast Community Mirror (`bmclapi2.bangbang93.com`) for regions where Mojang CDN is throttled
 * 3. Local Android Launcher Scanner (PojavLauncher, Zalith Launcher, Fold Craft Launcher, MojoLauncher, Download folder)
 * 4. On-Device Resource Preparer (extracts `assets/`, `data/`, `version.json`, `block-tags.json`,
 *    `damage-types.json`, `worldgen/*.json`, and merges pre-baked 26.3 exporter tables into
 *    `.local/horizon-client/26.3/horizon-resources.json`).
 */
enum class DownloadSourceKind(
    val id: String,
    val label: String,
    val description: String,
    val metaBaseUrl: String,
    val resourcesBaseUrl: String
) {
    MOJANG_OFFICIAL(
        id = "mojang",
        label = "Mojang Official CDN",
        description = "piston-meta.mojang.com & resources.download.minecraft.net (SHA-1 verified)",
        metaBaseUrl = "https://piston-meta.mojang.com",
        resourcesBaseUrl = "https://resources.download.minecraft.net"
    ),
    BMCLAPI_MIRROR(
        id = "bmclapi",
        label = "BMCLAPI High-Speed Mirror",
        description = "bmclapi2.bangbang93.com mirror (same Mojang SHA-1 pins, faster on mobile networks)",
        metaBaseUrl = "https://bmclapi2.bangbang93.com",
        resourcesBaseUrl = "https://bmclapi2.bangbang93.com/assets"
    ),
    LOCAL_ANDROID_LAUNCHERS(
        id = "local_scan",
        label = "Scan Phone (Pojav / Zalith / FCL / Downloads)",
        description = "Import existing 26.3 client.jar & assets from other Android launchers on storage",
        metaBaseUrl = "https://piston-meta.mojang.com",
        resourcesBaseUrl = "https://resources.download.minecraft.net"
    );
}

data class ResearchLockPins(
    val version: String = "26.3",
    val manifestSha1: String = "11f1aefe473eefba0af62e3286c33b692807b2eb",
    val acceptedManifestSha1s: Set<String> = setOf(
        "11f1aefe473eefba0af62e3286c33b692807b2eb",
        "4fe1aa1ef8da1cb95c5bad1fb98890ca56dd8ca3",
        "702fe59163c6ee6578607daa85811d9bc9c7cc40",
        "61fff1316bd50312624a858bbb5eb18fa45208ed",
        "5df1795e16eea19d75b923d66ecca9ce183353c5"
    ),
    val acceptedAssetIndexSha1s: Set<String> = setOf(
        "abfaa525f923f807df8b4e4d29c1b5e3a104adbe",
        "1e4e4a68dece1c0f51b8f93b45da8fe8da51bcd3",
        "0c8f43b28f837869c1e54a6aeb22c396a1548946",
        "917ff6d74b9f635c46bac0d28857206bbcc1ffe4"
    ),
    val clientSha1: String = "e877b6a07acd633fb3bb475002175cec036e7b87",
    val serverSha1: String = "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c",
    val dataVersion: Int = 5023,
    val protocolVersion: Int = 777,
    val javaMajor: Int = 25
)

data class PreparationStatus(
    val hasManifestJson: Boolean,
    val hasClientJar: Boolean,
    val hasServerJar: Boolean,
    val hasAssetIndex: Boolean,
    val hasHorizonResourcesJson: Boolean,
    val preparedDir: File,
    val minecraftDir: File,
    val detectedExternalRoots: List<Pair<String, File>>
) {
    val isReadyToPlay: Boolean
        get() = hasHorizonResourcesJson && File(preparedDir, "horizon-resources.json").isFile
}

object MinecraftDownloadManager {

    val PINS = ResearchLockPins()
    val cancelFlag = AtomicBoolean(false)

    private val WORLDGEN_BUNDLES = listOf(
        "noises" to "worldgen/noise",
        "densities" to "worldgen/density_function",
        "rules" to "worldgen/material_rule",
        "conditions" to "worldgen/material_condition",
        "settings" to "worldgen/noise_settings",
        "carvers" to "worldgen/carver",
        "biomes" to "worldgen/biome",
        "world_presets" to "worldgen/world_preset",
        "features" to "worldgen/feature",
        "placed_features" to "worldgen/placed_feature",
        "state_providers" to "worldgen/block_state_provider",
        "biome_tags" to "tags/worldgen/biome",
        "feature_tags" to "tags/worldgen/feature",
        "block_tags" to "tags/block",
        "fluid_tags" to "tags/fluid",
        "processor_lists" to "worldgen/processor_list",
        "template_pools" to "worldgen/template_pool",
        "structures" to "worldgen/structure",
        "structure_sets" to "worldgen/structure_set"
    )

    /**
     * Known Android Minecraft Java launcher directories (`sources.rs` equivalent for Android).
     */
    fun knownAndroidLauncherDirs(): List<Pair<String, File>> {
        val sdcard = File("/storage/emulated/0")
        val candidates = listOf(
            "PojavLauncher (games)" to File(sdcard, "games/PojavLauncher/.minecraft"),
            "PojavLauncher (Android/data)" to File(sdcard, "Android/data/net.kdt.pojavlaunch/files/.minecraft"),
            "PojavLauncher Debug" to File(sdcard, "Android/data/net.kdt.pojavlaunch.debug/files/.minecraft"),
            "Zalith Launcher" to File(sdcard, "games/ZalithLauncher/.minecraft"),
            "Zalith (Android/data)" to File(sdcard, "Android/data/com.movtery.zalithlauncher/files/.minecraft"),
            "Fold Craft Launcher (FCL)" to File(sdcard, "FCL/.minecraft"),
            "Fold Craft (games)" to File(sdcard, "games/FoldCraftLauncher/.minecraft"),
            "MojoLauncher" to File(sdcard, "games/MojoLauncher/.minecraft"),
            "Download Folder" to File(sdcard, "Download")
        )
        return candidates.filter { (_, dir) -> dir.exists() }
    }

    fun inspectStatus(baseDir: File): PreparationStatus {
        val mcDir = File(baseDir, "minecraft").apply { mkdirs() }
        val preparedDir = File(baseDir, ".local/horizon-client/${PINS.version}").apply { mkdirs() }
        val manifestFile = File(mcDir, "versions/${PINS.version}/${PINS.version}.json")
        val clientJar = File(mcDir, "versions/${PINS.version}/${PINS.version}.jar")
        val serverJar = File(baseDir, "server-jars/${PINS.version}.jar")
        val assetIndex = File(mcDir, "assets/indexes/34.json")
        val resourcesJson = File(preparedDir, "horizon-resources.json")

        return PreparationStatus(
            hasManifestJson = manifestFile.isFile && sha1File(manifestFile) in PINS.acceptedManifestSha1s,
            hasClientJar = clientJar.isFile && sha1File(clientJar) == PINS.clientSha1,
            hasServerJar = serverJar.isFile && sha1File(serverJar) == PINS.serverSha1,
            hasAssetIndex = assetIndex.isFile,
            hasHorizonResourcesJson = resourcesJson.isFile,
            preparedDir = preparedDir,
            minecraftDir = mcDir,
            detectedExternalRoots = knownAndroidLauncherDirs()
        )
    }

    /**
     * Full download + on-device preparation pipeline for Minecraft 26.3.
     * Can pull from Mojang Official CDN, BMCLAPI Mirror, or existing Android launchers on the phone.
     */
    fun downloadAndPrepare263(
        context: Context,
        baseDir: File,
        source: DownloadSourceKind,
        downloadSounds: Boolean = true,
        onProgress: (String, Float) -> Unit
    ): Result<File> = runCatching {
        cancelFlag.set(false)
        val mcDir = File(baseDir, "minecraft").apply { mkdirs() }
        val versionsDir = File(mcDir, "versions/${PINS.version}").apply { mkdirs() }
        val serverJarsDir = File(baseDir, "server-jars").apply { mkdirs() }
        val preparedDir = File(baseDir, ".local/horizon-client/${PINS.version}").apply { mkdirs() }

        val manifestFile = File(versionsDir, "${PINS.version}.json")
        val clientJar = File(versionsDir, "${PINS.version}.jar")
        val serverJar = File(serverJarsDir, "${PINS.version}.jar")

        // Step 1: If user chose LOCAL_ANDROID_LAUNCHERS, scan phone storage first
        if (source == DownloadSourceKind.LOCAL_ANDROID_LAUNCHERS) {
            onProgress("Scanning PojavLauncher / Zalith / FCL / Download folders for 26.3...", 0.05f)
            tryImportFromLocalAndroidLaunchers(mcDir, clientJar, manifestFile, onProgress)
        }

        // Step 2: Fetch & verify 26.3.json version manifest
        if (!manifestFile.isFile || sha1File(manifestFile) !in PINS.acceptedManifestSha1s) {
            onProgress("Downloading Minecraft ${PINS.version} manifest from ${source.label}...", 0.10f)
            val manifestUrl = "${source.metaBaseUrl}/v1/packages/${PINS.manifestSha1}/${PINS.version}.json"
            downloadVerifiedFile(manifestUrl, manifestFile, PINS.acceptedManifestSha1s)
        }

        val manifestJson = JSONObject(manifestFile.readText(StandardCharsets.UTF_8))
        val downloadsObj = manifestJson.getJSONObject("downloads")
        val clientObj = downloadsObj.getJSONObject("client")
        val serverObj = downloadsObj.getJSONObject("server")

        // Step 3: Download & verify 26.3 client.jar (SHA-1: e877b6a07acd633fb3bb475002175cec036e7b87)
        if (!clientJar.isFile || sha1File(clientJar) != PINS.clientSha1) {
            val rawUrl = clientObj.getString("url")
            val effectiveUrl = rewriteMirrorUrl(rawUrl, source)
            onProgress("Downloading Minecraft ${PINS.version} client.jar (31.3 MB)...", 0.20f)
            downloadVerifiedFile(effectiveUrl, clientJar, setOf(PINS.clientSha1)) { bytesRead, total ->
                val pct = if (total > 0) 0.20f + 0.30f * (bytesRead.toFloat() / total.toFloat()) else 0.35f
                onProgress("Downloading client.jar: ${bytesRead / (1024 * 1024)} / ${total / (1024 * 1024)} MB", pct)
            }
        }

        // Step 4: Download & verify 26.3 server.jar (SHA-1: 33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c)
        if (!serverJar.isFile || sha1File(serverJar) != PINS.serverSha1) {
            val rawUrl = serverObj.getString("url")
            val effectiveUrl = rewriteMirrorUrl(rawUrl, source)
            onProgress("Downloading Minecraft ${PINS.version} server.jar...", 0.52f)
            downloadVerifiedFile(effectiveUrl, serverJar, setOf(PINS.serverSha1)) { bytesRead, total ->
                val pct = if (total > 0) 0.52f + 0.15f * (bytesRead.toFloat() / total.toFloat()) else 0.60f
                onProgress("Downloading server.jar: ${bytesRead / (1024 * 1024)} / ${total / (1024 * 1024)} MB", pct)
            }
        }

        // Step 5: Download assetIndex (34.json) & Panorama / Font / Language / Sound objects
        val assetIndexObj = manifestJson.getJSONObject("assetIndex")
        val assetId = assetIndexObj.getString("id")
        val assetSha1 = assetIndexObj.getString("sha1")
        val assetUrl = rewriteMirrorUrl(assetIndexObj.getString("url"), source)
        val indexFile = File(mcDir, "assets/indexes/$assetId.json")
        if (!indexFile.isFile || sha1File(indexFile) !in (PINS.acceptedAssetIndexSha1s + assetSha1)) {
            onProgress("Downloading asset index ($assetId.json)...", 0.68f)
            downloadVerifiedFile(assetUrl, indexFile, PINS.acceptedAssetIndexSha1s + assetSha1)
        }

        // Copy asset-index.json into preparedDir
        indexFile.copyTo(File(preparedDir, "asset-index.json"), overwrite = true)

        // Download essential indexed assets (panorama, unifont, languages, and optionally sounds)
        val indexRoot = JSONObject(indexFile.readText(StandardCharsets.UTF_8))
        val objectsMap = indexRoot.getJSONObject("objects")
        val wantedAssets = mutableListOf<Triple<String, String, Long>>()
        val keysIter = objectsMap.keys()
        while (keysIter.hasNext()) {
            val key = keysIter.next()
            val isEssential = key == "pack.mcmeta" ||
                key.startsWith("minecraft/textures/gui/title/background/panorama_") ||
                key.startsWith("minecraft/font/") ||
                key.startsWith("minecraft/lang/") ||
                (downloadSounds && (key == "minecraft/sounds.json" || key.startsWith("minecraft/sounds/")))
            if (isEssential) {
                val entry = objectsMap.getJSONObject(key)
                wantedAssets.add(Triple(key, entry.getString("hash"), entry.optLong("size", 0L)))
            }
        }

        if (wantedAssets.isNotEmpty()) {
            onProgress("Fetching ${wantedAssets.size} indexed assets (panorama, fonts, lang, sounds)...", 0.72f)
            downloadAssetObjects(mcDir, preparedDir, wantedAssets, source, onProgress)
        }

        // Step 6: Extract client.jar & server.jar resources + unpack pre-baked 26.3 metadata on device
        onProgress("Extracting 26.3 textures, models, shaders, block tags & worldgen on device...", 0.88f)
        prepareHorizonResourcesOnDevice(context, clientJar, serverJar, preparedDir, onProgress)

        onProgress("Minecraft 26.3 Rust resources verified & ready to play!", 1.0f)
        preparedDir
    }

    /**
     * On-device port of `crates/horizon-prepare/src/lib.rs`:
     * 1. Unpacks `assets/horizon-26.3-metadata.zip` from APK assets if bundled by CI.
     * 2. Extracts all `assets/*`, `data/*`, and `pack.png` from `client.jar` (`extract_client_resources`).
     * 3. Builds `block-tags.json`, `damage-types.json`, `damage-tags.json`, and all 19 `worldgen/*.json`
     *    bundles + `worldgen/structure/minecraft/*.nbt` directly from `client.jar`.
     * 4. Writes `horizon-resources.json` (`schema: 7`, `data_version: 5023`).
     */
    fun prepareHorizonResourcesOnDevice(
        context: Context,
        clientJar: File,
        serverJar: File?,
        preparedDir: File,
        onProgress: (String, Float) -> Unit
    ) {
        preparedDir.mkdirs()

        // 1. Unpack pre-baked metadata bundle from APK assets if available
        runCatching {
            context.assets.open("horizon-26.3-metadata.zip").use { input ->
                val tmpZip = File(context.cacheDir, "horizon-26.3-metadata.zip")
                FileOutputStream(tmpZip).use { out -> input.copyTo(out) }
                ZipFile(tmpZip).use { zf ->
                    val entries = zf.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        val outFile = File(preparedDir, entry.name)
                        outFile.parentFile?.mkdirs()
                        zf.getInputStream(entry).use { zin ->
                            FileOutputStream(outFile).use { fout -> zin.copyTo(fout) }
                        }
                    }
                }
                tmpZip.delete()
            }
        }

        // 2. Extract assets/*, data/*, pack.png, and version.json from client.jar
        var extractedCount = 0
        var worldVersion = PINS.dataVersion
        ZipFile(clientJar).use { zip ->
            zip.getEntry("version.json")?.let { verEntry ->
                val verTxt = zip.getInputStream(verEntry).bufferedReader().readText()
                val verJson = JSONObject(verTxt)
                worldVersion = verJson.optInt("world_version", PINS.dataVersion)
            }

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (entry.isDirectory || name.contains("..")) continue
                if (!(name.startsWith("assets/") || name.startsWith("data/") || name == "pack.png")) {
                    continue
                }
                extractedCount++
                val target = File(preparedDir, name)
                if (target.isFile && target.length() == entry.size && crc32File(target) == entry.crc) {
                    continue
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }

            // 3. Build block-tags.json, damage-types.json, damage-tags.json, and worldgen/*.json
            onProgress("Building 26.3 block-tags.json & 19 worldgen bundles...", 0.94f)
            val blockTags = collectJsonResourcesFromZip(zip, "data/minecraft/tags/block/")
            File(preparedDir, "block-tags.json").writeText(blockTags.toString(2) + "
", StandardCharsets.UTF_8)

            val damageTypes = collectJsonResourcesFromZip(zip, "data/minecraft/damage_type/")
            File(preparedDir, "damage-types.json").writeText(damageTypes.toString(2) + "
", StandardCharsets.UTF_8)

            val damageTags = collectJsonResourcesFromZip(zip, "data/minecraft/tags/damage_type/")
            File(preparedDir, "damage-tags.json").writeText(damageTags.toString(2) + "
", StandardCharsets.UTF_8)

            val worldgenDir = File(preparedDir, "worldgen").apply { mkdirs() }
            for ((bundleName, relPrefix) in WORLDGEN_BUNDLES) {
                val bundle = collectJsonResourcesFromZip(zip, "data/minecraft/$relPrefix/")
                File(worldgenDir, "$bundleName.json").writeText(bundle.toString(), StandardCharsets.UTF_8)
            }

            // Extract structure .nbt templates
            val structPrefix = "data/minecraft/structure/"
            val allEntries = zip.entries()
            while (allEntries.hasMoreElements()) {
                val e = allEntries.nextElement()
                if (!e.isDirectory && e.name.startsWith(structPrefix) && e.name.endsWith(".nbt")) {
                    val rel = e.name.removePrefix(structPrefix)
                    val outNbt = File(worldgenDir, "structure/minecraft/$rel")
                    outNbt.parentFile?.mkdirs()
                    zip.getInputStream(e).use { zin ->
                        FileOutputStream(outNbt).use { fout -> zin.copyTo(fout) }
                    }
                }
            }
        }

        // 4. Write horizon-resources.json (schema 7, data_version 5023)
        val manifest = JSONObject().apply {
            put("schema", 7)
            put("minecraft", PINS.version)
            put("data_version", worldVersion)
            put("resource_files", extractedCount)
            put("client_sha256", sha256File(clientJar))
            if (serverJar != null && serverJar.isFile) {
                put("server_sha256", sha256File(serverJar))
            }
        }
        File(preparedDir, "horizon-resources.json")
            .writeText(manifest.toString(2) + "
", StandardCharsets.UTF_8)
    }

    private fun collectJsonResourcesFromZip(zip: ZipFile, prefix: String): JSONObject {
        val names = mutableListOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (!e.isDirectory && e.name.startsWith(prefix) && e.name.endsWith(".json")) {
                names.add(e.name)
            }
        }
        names.sort()
        val out = JSONObject()
        for (name in names) {
            val entry = zip.getEntry(name) ?: continue
            val text = zip.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).readText()
            val key = "minecraft:" + name.substring(prefix.length, name.length - 5)
            out.put(key, text)
        }
        return out
    }

    private fun tryImportFromLocalAndroidLaunchers(
        targetMcDir: File,
        targetClientJar: File,
        targetManifestFile: File,
        onProgress: (String, Float) -> Unit
    ) {
        for ((label, root) in knownAndroidLauncherDirs()) {
            val candidateJars = listOf(
                File(root, "versions/${PINS.version}/${PINS.version}.jar"),
                File(root, "libraries/com/mojang/minecraft/${PINS.version}/minecraft-${PINS.version}-client.jar"),
                File(root, "${PINS.version}.jar"),
                File(root, "client.jar")
            )
            for (cand in candidateJars) {
                if (cand.isFile && sha1File(cand) == PINS.clientSha1) {
                    onProgress("Found verified 26.3 client.jar in $label!", 0.15f)
                    targetClientJar.parentFile?.mkdirs()
                    cand.copyTo(targetClientJar, overwrite = true)
                    val candManifest = File(root, "versions/${PINS.version}/${PINS.version}.json")
                    if (candManifest.isFile && sha1File(candManifest) in PINS.acceptedManifestSha1s) {
                        candManifest.copyTo(targetManifestFile, overwrite = true)
                    }
                    return
                }
            }
        }
    }

    private fun downloadAssetObjects(
        mcDir: File,
        preparedDir: File,
        items: List<Triple<String, String, Long>>,
        source: DownloadSourceKind,
        onProgress: (String, Float) -> Unit
    ) {
        val pool = Executors.newFixedThreadPool(6)
        val done = AtomicInteger(0)
        val total = items.size
        try {
            val futures = items.map { (logicalPath, hash, _) ->
                pool.submit {
                    if (cancelFlag.get()) return@submit
                    val prefix = hash.substring(0, 2)
                    val objFile = File(mcDir, "assets/objects/$prefix/$hash")
                    if (!objFile.isFile || sha1File(objFile) != hash) {
                        val url = "${source.resourcesBaseUrl}/$prefix/$hash"
                        runCatching {
                            downloadVerifiedFile(url, objFile, setOf(hash))
                        }
                    }
                    if (objFile.isFile) {
                        val dest = File(preparedDir, "assets/$logicalPath")
                        dest.parentFile?.mkdirs()
                        if (!dest.isFile) {
                            objFile.copyTo(dest, overwrite = true)
                        }
                    }
                    val c = done.incrementAndGet()
                    if (c % 25 == 0 || c == total) {
                        val pct = 0.72f + 0.15f * (c.toFloat() / total.toFloat())
                        onProgress("Downloaded $c / $total assets...", pct)
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun rewriteMirrorUrl(url: String, source: DownloadSourceKind): String {
        if (source != DownloadSourceKind.BMCLAPI_MIRROR) return url
        return url
            .replace("https://piston-meta.mojang.com", "https://bmclapi2.bangbang93.com")
            .replace("https://piston-data.mojang.com", "https://bmclapi2.bangbang93.com")
            .replace("https://launcher.mojang.com", "https://bmclapi2.bangbang93.com")
            .replace("https://resources.download.minecraft.net", "https://bmclapi2.bangbang93.com/assets")
    }

    private fun downloadVerifiedFile(
        urlStr: String,
        destination: File,
        acceptedSha1s: Set<String>,
        onBytes: ((Long, Long) -> Unit)? = null
    ) {
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.partial")
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "DiamondLauncher/0.2.0 (Android; Horizon 26.3)")
        }
        try {
            val code = conn.responseCode
            require(code in 200..299) { "HTTP $code from $urlStr" }
            val totalLen = conn.contentLengthLong
            val sha1 = MessageDigest.getInstance("SHA-1")
            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(partial).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readTotal = 0L
                    while (true) {
                        if (cancelFlag.get()) throw InterruptedException("Download cancelled")
                        val n = input.read(buf)
                        if (n <= 0) break
                        sha1.update(buf, 0, n)
                        output.write(buf, 0, n)
                        readTotal += n
                        onBytes?.invoke(readTotal, totalLen)
                    }
                }
            }
            val actualHex = sha1.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            if (acceptedSha1s.isNotEmpty() && actualHex !in acceptedSha1s) {
                partial.delete()
                throw IllegalStateException("SHA-1 mismatch for ${destination.name}: got $actualHex")
            }
            if (destination.exists()) destination.delete()
            partial.renameTo(destination)
        } finally {
            conn.disconnect()
        }
    }

    fun sha1File(file: File): String = hashFile(file, "SHA-1")
    fun sha256File(file: File): String = hashFile(file, "SHA-256")

    private fun hashFile(file: File, algo: String): String {
        if (!file.isFile) return ""
        val md = MessageDigest.getInstance(algo)
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun crc32File(file: File): Long {
        val crc = CRC32()
        FileInputStream(file).use { fis ->
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = fis.read(buf)
                if (n <= 0) break
                crc.update(buf, 0, n)
            }
        }
        return crc.value
    }
}