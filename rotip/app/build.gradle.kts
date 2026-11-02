import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ksB64 = System.getenv("ROTIP_KEYSTORE_B64")
val ksPass = System.getenv("ROTIP_KEYSTORE_PASS")

android {
    namespace = "com.armorberserk.rotip"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.armorberserk.rotip"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (ksB64 != null && ksPass != null) {
            create("rotipRelease") {
                val tmp = File.createTempFile("rotip", ".keystore")
                tmp.writeBytes(Base64.getDecoder().decode(ksB64))
                storeFile = tmp
                storePassword = ksPass
                keyAlias = "rotip"
                keyPassword = ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksB64 != null && ksPass != null) {
                signingConfig = signingConfigs.getByName("rotipRelease")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // Root bridge
    implementation("com.github.topjohnwu.libsu:core:6.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
