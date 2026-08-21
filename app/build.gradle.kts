plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val secretsFile = rootProject.file(".secrets")
val secrets = mutableMapOf<String, String>()
if (secretsFile.exists()) {
    secretsFile.readLines().forEach { line ->
        val parts = line.split(":", limit = 2)
        if (parts.size == 2) {
            secrets[parts[0].trim()] = parts[1].trim()
        }
    }
}

fun releaseValue(propertyName: String, environmentName: String): String? =
    providers.gradleProperty(propertyName)
        .orElse(providers.environmentVariable(environmentName))
        .orNull
        ?.takeIf(String::isNotBlank)

val releaseKeystorePath = releaseValue("agenticdroid.release.storeFile", "AGENTICDROID_RELEASE_STORE_FILE")
val releaseStorePassword = releaseValue("agenticdroid.release.storePassword", "AGENTICDROID_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseValue("agenticdroid.release.keyAlias", "AGENTICDROID_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseValue("agenticdroid.release.keyPassword", "AGENTICDROID_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

android {
    namespace = "com.justnels.agenticdroid"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.justnels.agenticdroid"
        minSdk = 26
        // The bundled Node/git/qemu-user toolchain is downloaded into app-private
        // storage at runtime and executed directly from there. Android 10's target-29
        // W^X policy forbids execve() from app data, so this app must stay on the
        // API 28 compatibility policy when sideloaded. See AGENT_RUNTIME_RESEARCH.md
        // for a verified-on-device prototype (NodeRuntime.nativeLibBinary and the
        // combined DT_NEEDED dependency closure for qemu-user-aarch64, node, git, and
        // aapt2 - including running real installed agent CLIs, Claude Code and Codex, as
        // qemu's guest) showing the major arm64 lanes at raised targetSdk values. Python,
        // npm/npx, git HTTPS, the native helpers, and the patched OpenJDK launcher have
        // also passed at API 36. Not yet adopted app-wide - see
        // AGENT_RUNTIME_RESEARCH.md Sections 18-21 for the remaining gates.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // OAuth device flow is designed for public clients. A client secret must never
        // be compiled into an APK, where it is recoverable by anyone with the artifact.
        buildConfigField("String", "GH_CLIENT_ID", "\"${secrets["gh_client_id"] ?: ""}\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseKeystorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("main") {
            // These maintained, 16 KB-aligned rebuilds used to be APK assets only; no
            // runtime code ever copied that asset override into the downloaded Termux
            // tree. Package them as real native libraries so PackageManager installs
            // them in the W^X-exempt nativeLibraryDir instead.
            jniLibs.directories.add("src/main/assets/native-overrides/libandroid-spawn")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf(
                "**/libnpm_wrapper.so",
                "**/libnpx_wrapper.so",
                "**/libpip_wrapper.so",
                "**/libpip3_wrapper.so",
                "**/libkotlinc_wrapper.so",
                "**/libjdk_*_wrapper.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/NOTICE.md"
            // zstd-jni bundles native libs for every desktop platform (Windows/macOS/Linux
            // x86 and ARM) so the same jar works unmodified on the JVM. None of those can
            // ever load on Android - only lib/arm64-v8a/libzstd-jni-*.so (packaged via the
            // jniLibs mechanism below, unaffected by these excludes) is actually used here.
            excludes += "win/**"
            excludes += "darwin/**"
            excludes += "linux/**"
            excludes += "freebsd/**"
            excludes += "aix/**"
        }
    }
    lint {
        // AgenticDroid currently executes its sideloaded toolchain from app-private
        // storage, which requires the API 28 compatibility policy. Keep this explicit
        // and narrowly scoped; this build is not eligible for Play distribution.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.security.crypto)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.sshj)
    implementation(libs.apache.commons.compress)
    implementation(libs.tukaani.xz)
    implementation(libs.zstd.jni)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)
    implementation(libs.okhttp)
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:1.0.0")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:1.0.0")

    testImplementation(libs.junit)
    // Real org.json impl for JVM unit tests - the Android SDK's org.json classes on the
    // test classpath are stubs that throw "not mocked" on every call (e.g. JSONObject.put
    // in ProjectMetadata.toJson), since they're normally backed by the on-device runtime.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
