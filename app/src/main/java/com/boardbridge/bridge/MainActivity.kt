package com.boardbridge.bridge

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.diamondlauncher.ui.DiamondLauncherApp

/**
 * Primary Android Activity for **DiamondLauncher** — the Material You (Jetpack Compose)
 * Android port of **Horizon Launcher** (Minecraft 26.3 Java → Rust) powered by **BoardBridge**.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "DiamondLauncher"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!BridgeRuntime.init()) {
            Log.w(TAG, "Native boardbridge library running in JVM fallback mode")
        }

        setContent {
            DiamondLauncherApp()
        }
    }

    override fun onPause() {
        super.onPause()
        BridgeRuntime.pause()
    }

    override fun onResume() {
        super.onResume()
        BridgeRuntime.resume()
    }

    override fun onDestroy() {
        BridgeRuntime.shutdown()
        super.onDestroy()
    }
}