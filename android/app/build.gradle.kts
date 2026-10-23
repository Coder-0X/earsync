import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Locate keystore.properties from local directory or parent signing folder
val keystorePropertiesFile = rootProject.file("keystore.properties").takeIf { it.exists() }
    ?: file("C:/Users/NITRO V 15/earsync-signing/keystore.properties").takeIf { it.exists() }

val keystoreProperties = Properties()
if (keystorePropertiesFile != null && keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

android {
    namespace = "com.earsync.dualaudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.earsync.dualaudio"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProperties.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
            if (!storeFilePath.isNullOrEmpty()) {
                val resolvedStore = file(storeFilePath)
                if (resolvedStore.exists()) {
                    storeFile = resolvedStore
                    storePassword = keystoreProperties.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = keystoreProperties.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
                    keyPassword = keystoreProperties.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
}
