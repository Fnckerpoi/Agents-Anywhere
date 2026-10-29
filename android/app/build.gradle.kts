import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val officialServerUrl = "https://web.agents-anywhere.com"
val localSettings = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val debugServerUrl = providers.gradleProperty("agentsAnywhere.serverUrl")
    .orElse(localSettings.getProperty("agentsAnywhere.serverUrl", officialServerUrl))
    .get()

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r") + "\""

android {
    namespace = "com.agentsanywhere.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.agentsanywhere.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "2.1.0"
        buildConfigField("String", "OFFICIAL_SERVER_URL", officialServerUrl.asBuildConfigString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "OFFICIAL_SERVER_URL", debugServerUrl.asBuildConfigString())
        }
        create("embeddedDebug") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".embedded.payload"
            matchingFallbacks += "debug"
            // The assembled APK retains native dependencies; embedPayloadSidecar removes them
            // from the host-loaded archive. The embedded surface does not load native code.
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.sora.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.compose.shimmer)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.jlatexmath.android)
    implementation(libs.jlatexmath.android.font.cyrillic)
    implementation(libs.jlatexmath.android.font.greek)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.lucide.icons)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.okhttp)
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)
    implementation(libs.sora.oniguruma.native)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.termux.terminal.view)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.17")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// The embedded payload keeps every Java/Kotlin dependency of the main source set: the terminal,
// editor and QR screens are compiled into this variant and reference these libraries, so removing
// them would break the build. embedPayloadSidecar strips native libraries from the final
// payload archive, and the embedded surface never loads a native library.

tasks.named("check") {
    dependsOn(rootProject.tasks.named("checkLegacyRoutes"))
}

// Embedded payload contract: the host loads this APK from an asset and calls
// com.agentsanywhere.embedded.PayloadEntry through reflection. The sidecar pins the artifact
// identity so the host can reject a stale or swapped payload before loading it.
val embedPayloadSidecar by tasks.registering {
    group = "distribution"
    description = "Copies the embedded payload APK and writes the sidecar manifest for the host loader."
    dependsOn("assembleEmbeddedDebug")
    val payloadApk = layout.buildDirectory.file("outputs/apk/embeddedDebug/app-embeddedDebug.apk")
    val outputDir = layout.buildDirectory.dir("outputs/embedded")
    val entryClass = "com.agentsanywhere.embedded.PayloadEntry"
    val schemaVersion = 1
    val abiVersion = 1
    inputs.file(payloadApk).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("schemaVersion", schemaVersion)
    inputs.property("abiVersion", abiVersion)
    inputs.property("entryClass", entryClass)
    outputs.dir(outputDir)
    doLast {
        val source = payloadApk.get().asFile
        check(source.isFile) { "Missing embedded payload APK: ${source.absolutePath}" }
        val destinationDir = outputDir.get().asFile
        destinationDir.mkdirs()
        val copied = File(destinationDir, "aa-embedded-payload.apk")
        // Plan A: Strip native libraries by rewriting the APK archive, preserving all other members,
        // dex and resources.arsc intact. The standalone application keeps its own native libraries
        // because no packaging exclusion is configured on the build variants.
        ZipFile(source).use { zipIn ->
        ZipOutputStream(copied.outputStream().buffered()).use { out ->
            for (entry in zipIn.entries()) {
                if (entry.name.matches(Regex("^lib/[^/]+/[^/]+[.]so$"))) continue
                if (entry.name.matches(Regex("^META-INF/(MANIFEST[.]MF|[^/]+[.](SF|RSA|DSA|EC))$", RegexOption.IGNORE_CASE))) continue
                val newEntry = ZipEntry(entry.name)
                newEntry.time = entry.time
                out.putNextEntry(newEntry)
                zipIn.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(copied.readBytes()).joinToString("") { byte -> "%02x".format(byte) }
        val sidecar = File(destinationDir, "payload.json")
        sidecar.writeText(
            """{
  "schemaVersion": $schemaVersion,
  "abiVersion": $abiVersion,
  "entry": "$entryClass",
  "sha256": "$hash"
}
""",
        )
        logger.lifecycle("Embedded payload sidecar written: ${sidecar.absolutePath}")
    }
}
