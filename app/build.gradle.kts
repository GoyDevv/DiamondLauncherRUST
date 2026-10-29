import org.gradle.api.tasks.PathSensitivity
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val cargoProfile: String = (project.findProperty("cargoProfile") as String?) ?: "release"
val skipRust: Boolean = (project.findProperty("skipRust") as String?)?.toBoolean() ?: false
val cargoAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val cargoRustJniLibsDir: File = layout.buildDirectory.dir("rustJniLibs").get().asFile

android {
    namespace = "com.boardbridge.bridge"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.boardbridge.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-diamond"
        ndk { abiFilters.addAll(cargoAbis) }
    }

    sourceSets.getByName("main") { jniLibs.srcDir(cargoRustJniLibsDir) }
    packaging { jniLibs { useLegacyPackaging = false } }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
}

fun resolveNdkHome(): File? {
    for (variable in listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT")) {
        val value = System.getenv(variable)
        if (!value.isNullOrBlank()) return file(value)
    }
    val sdkRoot = System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
        ?: System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { properties ->
            Properties().apply { properties.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }
    val ndkVersion = android.ndkVersion
    if (sdkRoot.isNullOrBlank() || ndkVersion.isNullOrBlank()) return null
    return file("$sdkRoot/ndk/$ndkVersion")
}

val ndkHome: File? = resolveNdkHome()

val cargoBuildBoardBridge = tasks.register<Exec>("cargoBuildBoardBridge") {
    group = "boardbridge"
    description = "Cross-compiles the Rust bridge (boardbridge/) into build/rustJniLibs with cargo-ndk."
    workingDir = rootProject.file("boardbridge")
    inputs.dir(rootProject.file("boardbridge/src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("boardbridge/Cargo.toml"))
    inputs.property("profile", cargoProfile)
    outputs.dir(cargoRustJniLibsDir)

    val arguments = mutableListOf("cargo", "ndk", "--platform", "26")
    cargoAbis.forEach { abi -> arguments += listOf("-t", abi) }
    arguments += listOf("-o", cargoRustJniLibsDir.absolutePath, "build")
    if (cargoProfile == "release") arguments += "--release"
    commandLine(arguments)
    environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
    ndkHome?.let { environment("ANDROID_NDK_HOME", it.absolutePath) }

    doFirst {
        val ndk = ndkHome
        if (ndk == null || !ndk.isDirectory) {
            throw GradleException(
                "No Android NDK found for the Rust build. Set ANDROID_NDK_HOME (or " +
                    "sdk.dir in local.properties) and run `cargo install cargo-ndk --locked`; " +
                    "see docs/ANDROID.md. To build the Kotlin shell only, pass -PskipRust.",
            )
        }
    }
}

if (skipRust) {
    logger.lifecycle("skipRust is set: libboardbridge.so is not rebuilt from boardbridge/")
} else {
    tasks.named("preBuild").configure { dependsOn(cargoBuildBoardBridge) }
}
