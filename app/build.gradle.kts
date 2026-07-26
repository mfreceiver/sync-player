import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// === Version from git (go-around pattern, aligns with ocdroid) ===
// No hand-maintained version fields. Both values derived from git at config time:
//   versionName = <nearest-tag>-<short-hash>[-dirty]  (leading "v" stripped)
//     → always carries the commit anchor. release.sh / release-check.sh override
//     only the tag portion via -PreleaseVersion=<tag>; the hash always reflects HEAD.
//   versionCode = git rev-list --count HEAD  (monotonic int, auto-increments per
//     commit; 228 at adoption, strictly greater than every historical hand-bumped
//     code which maxed at 123 — so installable as upgrade).
// Do NOT hand-edit versionCode/versionName; tag a release via scripts/release.sh.
fun gitOut(vararg args: String): String = try {
    val proc = ProcessBuilder("git", *args)
        .directory(rootProject.projectDir)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    out
} catch (e: Exception) { "" }

val releaseVersionOverride = providers.gradleProperty("releaseVersion").orNull?.trim()?.orEmpty()
val gitVersionName: String = run {
    val nearestTag = gitOut("describe", "--tags", "--abbrev=0").removePrefix("v")
    val tagPart = releaseVersionOverride?.takeIf { it.isNotEmpty() } ?: nearestTag
    val shortHash = gitOut("rev-parse", "--short", "HEAD")
    val dirty = if (gitOut("status", "--porcelain").isEmpty()) "" else "-dirty"
    when {
        tagPart.isEmpty() && shortHash.isEmpty() -> "dev"
        tagPart.isEmpty() -> "$shortHash$dirty"
        else -> "$tagPart-$shortHash$dirty"
    }
}
val gitVersionCode: Int = gitOut("rev-list", "--count", "HEAD").toIntOrNull()?.coerceAtLeast(1) ?: 1

android {
    namespace = "cn.vectory.syncplayer"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) {
                props.load(propsFile.inputStream())
            }
            storeFile = file(props.getProperty("release.storeFile", "release.keystore"))
            storePassword = props.getProperty("release.storePassword", "")
            keyAlias = props.getProperty("release.keyAlias", "release")
            keyPassword = props.getProperty("release.keyPassword", "")
        }
    }

    defaultConfig {
        applicationId = "cn.vectory.syncplayer"
        minSdk = 34
        targetSdk = 35
        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Lint gate blocks on errors. Debt paid off in the CI scaffold round
    // (LocalContextGetResourceValueCall → stringResource, Range → getColumnIndexOrThrow,
    // UnsafeOptIn → @OptIn). warningsAsErrors=false keeps warnings informational
    // (aligns with ocdroid).
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // AndroidX Core
    implementation(libs.core.ktx)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)

    // Activity
    implementation(libs.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Media3 / ExoPlayer
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)

    // SMB
    implementation(libs.jcifs.ng)

    // BouncyCastle (MD4 provider for jcifs-ng NTLM auth)
    implementation(libs.bouncycastle.provider)

    // SFTP
    implementation(libs.sshj)

    // Security (encrypted preferences)
    implementation(libs.security.crypto)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // DataStore
    implementation(libs.datastore.preferences)

    // Coil image loading (Compose integration)
    implementation(libs.coil.compose)

    // Biometric authentication for app lock
    implementation(libs.biometric)
    implementation(libs.appcompat)

    // EXIF metadata extraction for image files
    implementation(libs.exifinterface)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)

    // Android Instrumentation Testing
    androidTestImplementation(libs.room.testing)

    // Android Instrumentation Testing
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.mockk.android)
}
