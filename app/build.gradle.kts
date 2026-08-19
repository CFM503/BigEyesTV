import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.bigeyes.tv"
    compileSdk = 34

    signingConfigs {
        create("release") {
            enableV1Signing = true
            enableV2Signing = true

            val localKeystore = file("keystore/bigeyes-release.jks")
            val rootKeystore = rootProject.file("keystore/bigeyes-release.jks")

            if (keystorePropertiesFile.exists()) {
                val storeFilePath = keystoreProperties.getProperty("storeFile")
                if (storeFilePath != null) {
                    storeFile = rootProject.file(storeFilePath.removePrefix("../"))
                }
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            } else if (localKeystore.exists()) {
                storeFile = localKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "bigeyes2026tv"
                keyAlias = System.getenv("KEY_ALIAS") ?: "bigeyes-key"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "bigeyes2026tv"
            } else if (rootKeystore.exists()) {
                storeFile = rootKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "bigeyes2026tv"
                keyAlias = System.getenv("KEY_ALIAS") ?: "bigeyes-key"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "bigeyes2026tv"
            }
        }
    }

    defaultConfig {
        applicationId = "com.bigeyes.tv"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val buildType = variant.buildType.name
            output.outputFileName = "BigEyesTV-v${variant.versionName}-${buildType}.apk"
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
        viewBinding = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Media3 / ExoPlayer for TV playback (HLS, MP4, DASH)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // Embedded HTTP Server
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Apple Property List (Binary & XML Plist) Parser & Generator
    implementation("com.googlecode.plist:dd-plist:1.30")

    // Test dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
