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
        // Every binary this app directly execve()'s (node, git, aapt2, npm/npx, qemu-user,
        // python/pip, the JDK tools, kotlinc) is routed through NodeRuntime.nativeLibBinary()
        // to the W^X-exempt nativeLibraryDir - see AGENT_RUNTIME_RESEARCH.md for the
        // per-binary verification history. Only arm64-v8a is bundled (see the ndk.abiFilters
        // below); RUST/GOLANG/CPP/SSG runner groups still execve() directly from app-private
        // storage and are gated out of new installs (RunnerPackageGroup.nativeLibPackaged)
        // until they go through the same closure work.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // The native-lib closure (jniLibs/) has only ever been built and verified for
        // arm64-v8a - see AGENT_RUNTIME_RESEARCH.md Section 10. Restrict the shipped build
        // to that ABI rather than let a device install with an incomplete/missing closure.
        ndk {
            abiFilters += "arm64-v8a"
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
            // The termux-terminal-emulator AAR (see the `termux` version ref in
            // libs.versions.toml) still bundles its own 4 KB-page-aligned libtermux.so at
            // this same path. tools/build_libtermux.py rebuilds a 16 KB-aligned replacement
            // into this module's own jniLibs/ - app-module sourceSets are merged before
            // library/AAR dependencies, so pickFirsts keeps ours and drops the AAR's.
            pickFirsts += "**/libtermux.so"
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
}

// jniLibs/ is gitignored (the fetched/built binaries are too large to commit - see
// tools/fetch_native_libs.py, fetch_python_native_libs.py, fetch_jvm_native_libs.py,
// build_openjdk_launcher.py, and build_libtermux.py). Without these files,
// NodeRuntime.nativeLibBinary() silently returns null everywhere and every execve() falls
// back to app-private storage - which W^X blocks at targetSdk 36, so the resulting APK
// would crash on first agent/build/terminal use instead of failing to build. A release
// build must catch that at build time, not ship it.
tasks.register("checkNativeLibClosure") {
    val dir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a").asFile
    val required = listOf(
        "libqemu_user_aarch64.so",
        "libnode_native_aarch64.so",
        "libgit_native_aarch64.so",
        "libaapt2_native_aarch64.so",
        // Overrides the termux-terminal-emulator AAR's own 4 KB-page-aligned copy at
        // the same path (see packaging.jniLibs.pickFirsts) - without this file present,
        // the AAR's copy wins instead and fails Play's 16 KB native-library requirement.
        "libtermux.so"
    )
    doLast {
        val missing = required.filter { !File(dir, it).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing native-lib closure file(s) required for targetSdk 36 W^X compliance: " +
                    "$missing (looked in ${dir.absolutePath}). Run tools/fetch_native_libs.py, " +
                    "fetch_python_native_libs.py, fetch_jvm_native_libs.py, and " +
                    "build_libtermux.py (build_openjdk_launcher.py runs automatically as part " +
                    "of fetch_jvm_native_libs.py) before a release build."
            )
        }
    }
}

afterEvaluate {
    tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
        dependsOn("checkNativeLibClosure")
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
    testImplementation("org.json:json:20260814")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
