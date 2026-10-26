import java.util.zip.ZipFile

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Remove only optional demo/alternative media. Keep SDK defaults, hold music,
// grammars and TLS roots so native initialization and call controls stay intact.
val unusedSdkMedia = listOf(
    "hello16000.wav", "hello8000.wav", "four_hands_together.mkv",
    "house_keeping.mkv", "its_a_game.mkv", "leaving_dreams.mkv", "soft_as_snow.mkv"
)

android {
    namespace = "dev.mysip"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.mysip"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.2"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    androidResources { ignoreAssetsPatterns += unusedSdkMedia }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Inspect the actual APK, including dependency assets and merged native libraries.
tasks.register("verifyDebugPackage") {
    group = "verification"
    description = "Check ARM64 libraries and essential Linphone resources in the APK"
    dependsOn("assembleDebug")
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    inputs.file(apk)
    doLast {
        ZipFile(apk.get().asFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            val nativeLibraries = entries.filter { it.startsWith("lib/") && it.endsWith(".so") }.toSet()
            val requiredLibraries = setOf(
                "libZXing.so", "libbctoolbox.so", "libc++_shared.so", "libjpeg.so", "libjsoncpp.so",
                "liblinphone.so", "libmediastreamer2.so", "libmsaaudio.so", "libmsandroidcamera2.so",
                "libmswebrtc.so", "libortp.so", "libsrtp2.so", "libturbojpeg.so"
            ).map { "lib/arm64-v8a/$it" }.toSet()
            check(nativeLibraries == requiredLibraries) { "Unexpected native libraries: $nativeLibraries" }
            val requiredAssets = listOf(
                "belr/grammars/cpim_grammar.belr", "belr/grammars/ics_grammar.belr",
                "belr/grammars/identity_grammar.belr", "belr/grammars/mwi_grammar.belr",
                "belr/grammars/sdp_grammar.belr", "belr/grammars/sip_grammar.belr",
                "belr/grammars/vcard3_grammar.belr", "belr/grammars/vcard_grammar.belr",
                "linphone/rootca.pem", "images/nowebcamcif.jpg",
                "sounds/linphone/dont_wait_too_long.mkv", "sounds/linphone/ringback.wav",
                "sounds/linphone/toy-mono.wav", "sounds/linphone/incoming_chat.wav",
                "sounds/linphone/silence.mkv", "sounds/linphone/rings/notes_of_the_optimistic.mkv",
                "sounds/linphone/rings/oldphone-mono.wav"
            )
            requiredAssets.forEach { path ->
                val entry = zip.getEntry("assets/org.linphone.core/share/$path")
                check(entry != null && entry.size > 0) { "Missing SDK resource: $path" }
            }
            check(entries.none { it.substringAfterLast('/') in unusedSdkMedia }) { "Unused SDK media still packaged" }
            check("assets/xposed_init" in entries) { "Missing LSPosed entry point" }
            logger.lifecycle("APK verified: 13 ARM64 libraries, essential SDK resources and LSPosed entry point")
        }
    }
}
dependencies {
    val localSdk = providers.gradleProperty("linphoneAar").orNull
    if (localSdk != null) implementation(files(localSdk))
    else implementation("org.linphone:linphone-sdk-android:5.4.124")
    implementation("androidx.core:core:1.13.1")
    val localXposedApi = providers.gradleProperty("xposedApi").orNull
    if (localXposedApi != null) compileOnly(files(localXposedApi))
    else compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
