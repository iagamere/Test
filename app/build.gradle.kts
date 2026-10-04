plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.abdo.ps4monitor"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.abdo.ps4monitor"
        minSdk = 26; targetSdk = 34; versionCode = 9; versionName = "3.1"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore"); storePassword = "android"
            keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("commons-net:commons-net:3.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
