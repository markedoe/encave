import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Version name: one source for both apps, MARKETING_VERSION in apple/project.yml
// (read at configuration time; the fallback is for a checkout without apple/).
val marketingVersion: String = providers.fileContents(rootProject.layout.projectDirectory.file("../apple/project.yml"))
    .asText.orNull
    ?.let { Regex("""MARKETING_VERSION:\s*"([0-9]+(?:\.[0-9]+)*)"""").find(it)?.groupValues?.get(1) }
    ?: "0.1.0"

// Version code: BUILD_NUMBER, set by CI with the formula of apple.yml
// (1000 + run number * 10 + attempt), so it always increases. 1 when absent.
val buildNumber: Int = providers.environmentVariable("BUILD_NUMBER").orNull?.takeIf { it.isNotBlank() }?.let { raw ->
    raw.trim().toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
        ?: throw GradleException("BUILD_NUMBER must be a positive integer up to 2100000000, got \"$raw\"")
} ?: 1

// Release signing, from the environment only (CI decodes the upload keystore into a
// temporary file). Without all four variables the release build is left unsigned:
// it never falls back to the debug key.
val signingVars: Map<String, String> = listOf(
    "ENCAVE_KEYSTORE_PATH", "ENCAVE_KEYSTORE_PASSWORD", "ENCAVE_KEY_ALIAS", "ENCAVE_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull.orEmpty() }
val releaseKeystore: Map<String, String>? = signingVars.takeIf { vars -> vars.values.all { it.isNotEmpty() } }
if (releaseKeystore == null && signingVars.values.any { it.isNotEmpty() }) {
    logger.warn("Release signing needs all of ${signingVars.keys.joinToString()}: the release build is unsigned.")
}

android {
    namespace = "app.encave"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.encave"
        minSdk = 35
        targetSdk = 37
        versionCode = buildNumber
        versionName = marketingVersion
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore.getValue("ENCAVE_KEYSTORE_PATH"))
                storePassword = releaseKeystore.getValue("ENCAVE_KEYSTORE_PASSWORD")
                keyAlias = releaseKeystore.getValue("ENCAVE_KEY_ALIAS")
                keyPassword = releaseKeystore.getValue("ENCAVE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    androidResources {
        localeFilters += listOf("en", "fr")
    }

    lint {
        abortOnError = true
        disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // The tokens test checks DesignTokens.kt against the shared file.
            it.systemProperty("encave.tokens", rootProject.file("../design/tokens.json").absolutePath)
            // Robolectric intercepts FileDescriptor.sync(), used by the atomic cellar save.
            it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            // Screen tour screenshots (uploaded by CI, never committed).
            it.systemProperty("encave.screens", layout.buildDirectory.dir("screens").get().asFile.absolutePath)
            it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.adaptive)
    implementation(libs.adaptive.layout)
    implementation(libs.adaptive.navigation)
    // Device sync: relay rounds in the background.
    implementation(libs.androidx.work)
    // Home screen widgets (Jetpack Glance), drawn from a summary kept on the device.
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    // Pairing: scanning the other device's QR code on the device (no ML Kit, which reports to Google).
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.cpp)
    // Drawing this device's pairing QR code (encoder only, pure Java).
    implementation(libs.zxing.core)
    debugImplementation(libs.compose.ui.tooling)

    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.glance.appwidget.testing)
}
