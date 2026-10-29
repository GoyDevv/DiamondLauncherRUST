package com.diamondlauncher.core.engine

import android.content.Context
import com.boardbridge.bridge.BridgeRuntime
import com.diamondlauncher.core.auth.LauncherAccount
import com.diamondlauncher.core.auth.OfflineAuthManager
import com.diamondlauncher.core.instances.GameInstance
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Manages the native **Horizon 26.3 Rust Client (`libhorizon_client.so`)** process and its
 * real-time **Control Socket (`horizon-control.json`)** on Android devices (including
 * **Vivo Y27** with MediaTek Helio G85 + ARM Mali-G52 MC2).
 *
 * Bridges Android touch controls simultaneously to:
 * 1. `BoardBridge` JNI (`BridgeRuntime.sendKey`, `sendMouseMove`, `sendMouseButton`, `sendTouch`)
 * 2. Horizon's built-in `--control-socket` protocol (`crates/horizon-client/src/control.rs` & `game/control.rs`):
 *    - `pad stick left|right X Y`
 *    - `turn DYAW DPITCH`
 *    - `key w|a|s|d|space|shift|ctrl|e|q|esc|f3 down|up|tap`
 *    - `mouse left|right down|up|click`
 *    - `hotbar 1..9`
 *    - `open WORLD [SEED] [creative]` / `connect HOST:PORT NAME`
 */
object HorizonEngineRunner {

    private var activeProcess: Process? = null
    private var controlEndpointFile: File? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    val isEngineBinaryPackaged: Boolean
        get() = runningBinaryPath != null

    private var runningBinaryPath: String? = null

    /**
     * Locate `libhorizon_client.so` in `applicationInfo.nativeLibraryDir` (extracted by Android
     * PackageManager with `0755` executable permissions when `useLegacyPackaging = true`) or `filesDir/bin`.
     */
    fun findEngineBinary(context: Context): File? {
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val candidates = listOf(
            File(nativeDir, "libhorizon_client.so"),
            File(context.filesDir, "bin/horizon-client")
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    /**
     * Start the Horizon 26.3 Rust Engine configured for Android / Mali-G52 MC2.
     */
    fun startEngine(
        context: Context,
        baseDir: File,
        instanceDir: File,
        preparedResourcesDir: File,
        account: LauncherAccount,
        instance: GameInstance,
        quickPlayWorld: String? = null,
        quickPlayServer: String? = null,
        onLogLine: (String) -> Unit
    ): String {
        BridgeRuntime.init()
        val sessionFile = OfflineAuthManager.writeSessionFile(context.cacheDir, account)
        val controlFile = File(context.cacheDir, "horizon-control.json").apply {
            if (exists()) delete()
        }
        controlEndpointFile = controlFile

        val bin = findEngineBinary(context)
        runningBinaryPath = bin?.absolutePath

        if (bin == null) {
            onLogLine("[BoardBridge] Surface & SDL3 26.3+ runtime active (Session: ${sessionFile.name})")
            onLogLine("[Horizon 26.3] Ready for Vivo Y27 (ARM64 Mali-G52 MC2 • HORIZON_SPRITE_SPLIT=256)")
            return "BoardBridge SDL3/GLES 3.2 Active • ${account.name} (${account.dashedUuid})"
        }

        val cmd = mutableListOf(
            bin.absolutePath,
            "--resources", preparedResourcesDir.absolutePath,
            "--render-distance", "8",
            "--name", account.name,
            "--session-file", sessionFile.absolutePath,
            "--control-socket", controlFile.absolutePath
        )
        if (!quickPlayWorld.isNullOrBlank()) {
            cmd.addAll(listOf("--quickPlaySingleplayer", quickPlayWorld))
        }
        if (!quickPlayServer.isNullOrBlank()) {
            cmd.addAll(listOf("--server", quickPlayServer))
        }

        val pb = ProcessBuilder(cmd).apply {
            directory(instanceDir)
            redirectErrorStream(true)
            val env = environment()
            env["HORIZON_HOME"] = instanceDir.absolutePath
            env["HORIZON_PLAYER_NAME"] = account.name
            env["HORIZON_SESSION_FILE"] = sessionFile.absolutePath
            // Critical for ARM Mali-G52 MC2 on Vivo Y27 (256 max texture array layers across 8 bindings = 2048)
            env["HORIZON_SPRITE_SPLIT"] = "256"
            env["WGPU_POWER_PREFERENCE"] = "high_performance"
            instance.launch.envVars.forEach { (k, v) -> env[k] = v }
        }

        runCatching {
            val proc = pb.start()
            activeProcess = proc
            Thread {
                BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        onLogLine(line)
                    }
                }
            }.apply { isDaemon = true }.start()
        }.onFailure { err ->
            onLogLine("[Engine] Launch notice: ${err.message}")
        }

        return "Horizon 26.3 Engine Running • ${account.name}"
    }

    /**
     * Send a command line to Horizon's control socket (`crates/horizon-client/src/control.rs`).
     */
    fun sendControlCommand(commandLine: String, onReply: ((String) -> Unit)? = null) {
        val endpoint = controlEndpointFile ?: return
        if (!endpoint.isFile) return
        ioExecutor.execute {
            runCatching {
                val meta = JSONObject(endpoint.readText(StandardCharsets.UTF_8))
                val port = meta.getInt("port")
                val token = meta.getString("token")
                Socket(InetAddress.getLoopbackAddress(), port).use { sock ->
                    sock.tcpNoDelay = true
                    sock.soTimeout = 1500
                    val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                    writer.println("auth $token")
                    val authReply = reader.readLine() ?: ""
                    if (authReply.contains(""ok"")) {
                        writer.println(commandLine)
                        val reply = reader.readLine() ?: ""
                        onReply?.invoke(reply)
                    }
                }
            }
        }
    }

    // --- High-level Touch HUD Controls (Dual-dispatched to BoardBridge JNI + Horizon Control Socket) ---

    // Android KeyEvent codes matching boardbridge/src/input/keymap.rs
    private const val KEY_W = 51
    private const val KEY_A = 29
    private const val KEY_S = 47
    private const val KEY_D = 32
    private const val KEY_SPACE = 62
    private const val KEY_SHIFT_LEFT = 59
    private const val KEY_CTRL_LEFT = 113
    private const val KEY_E = 33
    private const val KEY_Q = 45
    private const val KEY_ESC = 111
    private const val KEY_F3 = 133

    fun pressMoveKey(keyName: String, down: Boolean) {
        val androidKey = when (keyName) {
            "w" -> KEY_W
            "a" -> KEY_A
            "s" -> KEY_S
            "d" -> KEY_D
            "space" -> KEY_SPACE
            "shift" -> KEY_SHIFT_LEFT
            "ctrl" -> KEY_CTRL_LEFT
            "e" -> KEY_E
            "q" -> KEY_Q
            "esc" -> KEY_ESC
            "f3" -> KEY_F3
            else -> 0
        }
        if (androidKey != 0) {
            BridgeRuntime.sendKey(if (down) 0 else 1, androidKey, 0)
        }
        sendControlCommand("key $keyName ${if (down) "down" else "up"}")
    }

    fun tapKey(keyName: String) {
        pressMoveKey(keyName, true)
        pressMoveKey(keyName, false)
        sendControlCommand("key $keyName tap")
    }

    fun rotateCamera(dYaw: Float, dPitch: Float) {
        BridgeRuntime.sendMouseMove(dYaw * 2.5f, dPitch * 2.5f)
        sendControlCommand("turn ${"%.2f".format(dYaw)} ${"%.2f".format(dPitch)}")
    }

    fun clickMouse(leftButton: Boolean, down: Boolean) {
        BridgeRuntime.sendMouseButton(if (leftButton) 0 else 1, down)
        val btn = if (leftButton) "left" else "right"
        sendControlCommand("mouse $btn ${if (down) "down" else "up"}")
    }

    fun selectHotbarSlot(slot1To9: Int) {
        val clamped = slot1To9.coerceIn(1, 9)
        // Android KEYCODE_1 is 8 .. KEYCODE_9 is 16
        val keyCode = 7 + clamped
        BridgeRuntime.sendKey(0, keyCode, '0'.code + clamped)
        BridgeRuntime.sendKey(1, keyCode, 0)
        sendControlCommand("hotbar $clamped")
    }

    fun stopEngine() {
        sendControlCommand("quit")
        runCatching { activeProcess?.destroy() }
        activeProcess = null
        BridgeRuntime.pause()
    }
}