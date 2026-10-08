plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.nudsg.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nudsg.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        buildConfigField("String", "DEFAULT_API_BASE_URL", "\"http://127.0.0.1:11434\"")
    }
    buildFeatures { buildConfig = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
}