import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "fi.bundo"
    compileSdk = 36
    defaultConfig {
        applicationId = "fi.bundo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        create("local") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".local"
            versionNameSuffix = "-local"
            matchingFallbacks += "debug"
        }
        getByName("debug") {
            val api = providers.gradleProperty("bundoApiBaseUrl").orElse("http://10.0.2.2:7275/api").get()
            require(api == "http://10.0.2.2:7275/api" ||
                api == "https://func-bun-do-dev-qrquvcgmhocc6.azurewebsites.net/api")
            buildConfigField("String", "IDENTITY_API_BASE", "\"$api\"")
        }
        getByName("local") {
            buildConfigField("String", "IDENTITY_API_BASE", "\"http://10.0.2.2:7275/api\"")
        }
        getByName("release") {
            buildConfigField("String", "IDENTITY_API_BASE",
                "\"https://func-bun-do-dev-qrquvcgmhocc6.azurewebsites.net/api\"")
        }
    }
    for (variant in listOf("debug", "local")) {
        sourceSets[variant].java.srcDir("src/authCheck/java")
        sourceSets[variant].res.srcDir("src/authCheck/res")
    }
    sourceSets["release"].java.srcDir("src/authCheck/java")
    for (variant in listOf("debug", "release")) {
        sourceSets[variant].java.srcDir("src/microsoft/java")
        sourceSets[variant].res.srcDir("src/microsoft/res")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
    testOptions { animationsDisabled = true }
    lint {
        warningsAsErrors = true
        // Toolchain/dependency upgrades are deliberate, not time-dependent lint failures.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        // v1 targets ARM64 phones and ARM64 emulator profiles, not ChromeOS.
        disable += "ChromeOsAbiSupport"
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// The publisher distributes its JNI runtime as an AAR, not a Maven module.
// Pin and verify the actual bytes before Gradle may consume them.
val sherpaArchive = layout.buildDirectory.file("speech-runtime/sherpa-onnx-1.13.7.aar")
val sherpaSha256 = "c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"
fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
val prepareSherpa by tasks.registering {
    outputs.file(sherpaArchive)
    outputs.upToDateWhen {
        sherpaArchive.get().asFile.let { it.isFile && sha256(it) == sherpaSha256 }
    }
    doLast {
        val target = sherpaArchive.get().asFile
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, "download.part")
        try {
            URI("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar")
                .toURL().openConnection().apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }.getInputStream().use { source -> partial.outputStream().use { source.copyTo(it) } }
            check(partial.length() == 49_113_869L && sha256(partial) == sherpaSha256) {
                "Speech runtime checksum mismatch"
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }
}
val sherpaRuntime = files(sherpaArchive).builtBy(prepareSherpa)

dependencies {
    debugImplementation("com.microsoft.identity.client:msal:8.4.2")
    releaseImplementation("com.microsoft.identity.client:msal:8.4.2")
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    // Room's migration reader requires 1.8.1. Otherwise the app pins tests to
    // Lifecycle's older core runtime while Room brings a newer JSON runtime.
    implementation(platform("org.jetbrains.kotlinx:kotlinx-serialization-bom:1.8.1"))
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("net.zetetic:sqlcipher-android:4.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation(sherpaRuntime)
    ksp("androidx.room:room-compiler:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
