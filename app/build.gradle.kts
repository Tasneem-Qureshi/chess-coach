import java.io.FileInputStream
import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Stockfish release tag (e.g. sf_19). The weekly workflow bumps this file when a new engine ships.
val stockfishTag = rootProject.file("engine-version.txt").readText().trim()

// Release signing: CI writes keystore.properties from repository secrets; local builds may provide their own.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.tasneem.chesscoach"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tasneem.chesscoach"
        minSdk = 29
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0.0-dev"

        buildConfigField("String", "GITHUB_REPO", "\"Tasneem-Qureshi/chess-coach\"")
        buildConfigField("String", "CONTENT_URL", "\"https://raw.githubusercontent.com/Tasneem-Qureshi/chess-coach/main/content\"")
        buildConfigField("String", "ENGINE_VERSION", "\"$stockfishTag\"")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
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
    // Stockfish ships as an executable named lib*.so so Android installs it into nativeLibraryDir,
    // the only app location that may be executed on Android 10+. It must stay extracted on disk.
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    // The weekly puzzles / openings in /content are bundled so the app works offline on first launch.
    sourceSets.getByName("main").assets.directories.add(rootProject.file("content").path)
}

// Downloads the official Stockfish Android build once and places it as a native lib.
val fetchStockfish = tasks.register("fetchStockfish") {
    val out = file("src/main/jniLibs/arm64-v8a/libstockfish.so")
    val stamp = file("src/main/jniLibs/arm64-v8a/.version")
    val tag = stockfishTag
    val tmp = layout.buildDirectory.dir("stockfish").get().asFile
    outputs.files(out, stamp)
    onlyIf { !out.exists() || !stamp.exists() || stamp.readText().trim() != tag }
    doLast {
        tmp.mkdirs()
        val tgz = File(tmp, "sf.tar.gz")
        val url = "https://github.com/official-stockfish/Stockfish/releases/download/$tag/stockfish-android-arm64-universal.tar.gz"
        logger.lifecycle("Downloading Stockfish $tag")
        URI(url).toURL().openStream().use { input -> tgz.outputStream().use { input.copyTo(it) } }
        val p = ProcessBuilder("tar", "xzf", tgz.path, "-C", tmp.path).inheritIO().start()
        check(p.waitFor() == 0) { "could not unpack $tgz" }
        val bin = tmp.walkTopDown().first { it.isFile && it.name.startsWith("stockfish-android-") && !it.name.endsWith(".tar.gz") }
        out.parentFile.mkdirs()
        bin.copyTo(out, overwrite = true)
        stamp.writeText(tag)
    }
}
tasks.named("preBuild") { dependsOn(fetchStockfish) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
