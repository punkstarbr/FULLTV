import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val releaseKeystorePath = providers.environmentVariable("KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
val releaseCertificateSha256 = providers.environmentVariable("RELEASE_CERT_SHA256").orNull
    ?.trim()
    ?.lowercase()
val fullTvVersionName = "0.11.39"
val fullTvVersionCode = 51
val hasReleaseSigning = releaseKeystorePath?.let { it.isNotBlank() && file(it).isFile } == true &&
    !releaseKeystorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank() &&
    releaseCertificateSha256?.matches(Regex("[0-9a-f]{64}")) == true
val chaquopyWheelhouse = project.layout.buildDirectory.dir("chaquopy-wheelhouse").get().asFile

dependencyLocking {
    lockAllConfigurations()
}

// OSV advisories found old versions in the shipped Android graph and AGP's
// project-local test-platform configurations. Keep both APK and build classpaths patched.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        when {
            requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true -> {
                useVersion("4.1.137.Final")
                because("Resolve the published Netty 4.1 security advisories")
            }
            requested.group == "com.google.protobuf" &&
                requested.version?.startsWith("3.") == true -> {
                useVersion("3.25.5")
                because("Resolve GHSA-735f-pc8j-v9w8")
            }
            requested.group == "org.apache.httpcomponents" && requested.name == "httpclient" -> {
                useVersion("4.5.14")
                because("Resolve GHSA-7r82-7xv7-xcpj; Apache fixed it in 4.5.13")
            }
            requested.group == "com.google.guava" && requested.name == "guava" -> {
                val flavor = if (requested.version?.endsWith("-jre") == true) "jre" else "android"
                useVersion("33.7.2-$flavor")
                because("Resolve GHSA-xxph-c9ww-hj94")
            }
        }
    }
}

val verifyChaquopyWheelHashes = tasks.register(
    "verifyChaquopyWheelHashes",
    org.gradle.api.tasks.Exec::class.java,
) {
    group = "verification"
    description = "Verifies pinned Python wheel SHA-256 hashes for every Android ABI."
    commandLine(
        "python3.11",
        rootProject.file("tools/verify_chaquopy_wheel_hashes.py").absolutePath,
        "python3.11",
        project.file("requirements-android.txt").absolutePath,
        "--wheelhouse",
        chaquopyWheelhouse.absolutePath,
    )
    inputs.file(rootProject.file("tools/verify_chaquopy_wheel_hashes.py"))
    inputs.file(project.file("requirements-android.txt"))
    outputs.dir(chaquopyWheelhouse)
}

// Re-hash the exact wheel files immediately before Chaquopy's per-ABI install. This task has no
// outputs and is forced to run every build, so a changed wheel cannot be installed from a stale
// preflight result. The Python requirements file is a full transitive lock; pip's own hash mode
// rejects Chaquopy's ABI-specific libxml/libxslt dependency edges, so the offline verified wheelhouse
// is the installation trust boundary instead.
val verifyChaquopyWheelhouse = tasks.register<org.gradle.api.tasks.Exec>("verifyChaquopyWheelhouse") {
    group = "verification"
    description = "Revalidates exact pinned wheel bytes immediately before Chaquopy installation."
    dependsOn(verifyChaquopyWheelHashes)
    commandLine(
        "python3.11",
        rootProject.file("tools/verify_chaquopy_wheelhouse.py").absolutePath,
        project.file("requirements-android.txt").absolutePath,
        chaquopyWheelhouse.absolutePath,
    )
    inputs.file(rootProject.file("tools/verify_chaquopy_wheelhouse.py"))
    inputs.file(rootProject.file("tools/verify_chaquopy_wheel_hashes.py"))
    inputs.file(project.file("requirements-android.txt"))
    inputs.file(chaquopyWheelhouse.resolve("wheelhouse-manifest.json"))
    inputs.files(fileTree(chaquopyWheelhouse) { include("*.whl") })
    outputs.upToDateWhen { false }
}

tasks.configureEach {
    if (name == "installDebugPythonRequirements" || name == "installReleasePythonRequirements") {
        dependsOn(verifyChaquopyWheelhouse)
    }
}

android {
    namespace = "app.opentv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.fulltv"
        targetSdk = 35
        minSdk = 24
        ndk {
            // Chaquopy 3.11 builds support these four ABIs, including 32-bit TV boxes.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        versionCode = fullTvVersionCode
        versionName = fullTvVersionName
        // FullTV publishes versioned GitHub Releases; older releases remain available there.
        buildConfigField("String", "UPDATE_REPO_SLUG", "\"punkstarbr/FULLTV\"")
        buildConfigField(
            "String",
            "RELEASE_SIGNING_CERT_SHA256",
            "\"${releaseCertificateSha256.orEmpty()}\"",
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("upload") {
            if (hasReleaseSigning) {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // Never fall back to the Android debug keystore. The preflight below also makes
            // assembleRelease fail closed when the production inputs are absent.
            signingConfig = signingConfigs.getByName("upload")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += "META-INF/LICENSE.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

val verifyReleaseSigning = tasks.register<org.gradle.api.tasks.Exec>("verifyReleaseSigning") {
    group = "verification"
    description = "Validates that release signing is explicitly configured."
    commandLine(
        "python3.11",
        rootProject.file("tools/verify_release_signing.py").absolutePath,
        "--version",
        fullTvVersionName,
        "--version-code",
        fullTvVersionCode.toString(),
    )
}

tasks.configureEach {
    if (name == "assembleRelease" || name == "packageRelease" ||
        name == "bundleRelease" || name == "signReleaseBundle") {
        dependsOn(verifyReleaseSigning)
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        buildPython("python3.11")
        pip {
            // Every ABI install consumes the exact wheelhouse re-hashed immediately before this
            // task. Do not permit an index/cache fallback or a second online download. All
            // transitive packages are direct pins in requirements-android.txt.
            options(
                "--no-index",
                "--find-links=${chaquopyWheelhouse.absolutePath}",
                "--only-binary=:all:",
                "--no-deps",
            )
            install("-r", "requirements-android.txt")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.transformer)
    implementation(libs.mlkit.translation)
    implementation(libs.mlkit.language.id)
    implementation(libs.vosk.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.commons.net)
    implementation(libs.xz)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    // Storage Access Framework helpers - write recordings to a plugged-in USB / external
    // drive via a user-granted tree URI, with no storage permission.
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)
    implementation(libs.guava.android)

    // SMB/CIFS client for recording to a NAS (Synology etc.) and playing those recordings
    // back in-app over the network. Pure-Java SMB2/3, no native bits.
    implementation("com.hierynomus:smbj:0.12.2")
    // X.509 local TLS certificates; keep Bouncy Castle aligned with the patched release because
    // SMBJ also brings an older transitive bcprov version.
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.conscrypt.openjdk.uber)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
}
