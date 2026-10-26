plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.mysip"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.mysip"
        minSdk = 28
        targetSdk = 35
        versionCode = 4
        versionName = "0.2.1"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
