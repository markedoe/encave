import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent cellar model, the Kotlin port of apple/Packages/CaveKit.
// No Android dependencies, so it builds and tests on any JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // Sync protocol: suspend functions over a transport.
    api(libs.kotlinx.coroutines.core)
    // X25519 and ChaCha20-Poly1305 on every Android version (the platform has them
    // only from API 33 and 28). Lightweight API only; no provider is registered.
    implementation(libs.bouncycastle)
    // WebDAV sync (PROPFIND, MKCOL, which HttpURLConnection refuses). Sends only
    // what the store asks for, to the server the user chose.
    implementation(libs.okhttp)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // The relay integration test runs when CI starts relay/server.mjs.
    System.getenv("ENCAVE_RELAY_URL")?.let { environment("ENCAVE_RELAY_URL", it) }
}
