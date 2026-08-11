plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qring.print"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qring.print"
        minSdk = 33          // Android 13+：BLE 新 API（旧 API 在 SDK34 编译时 HIDDEN）
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // 正式签名（2026-08-11 生成 release.jks；密码在 android/keystore-password.txt）。
    // 必须先于 buildTypes 声明（buildTypes 引用 signingConfigs）
    signingConfigs {
        create("release") {
            storeFile = file("../release.jks")
            storePassword = providers.gradleProperty("QRING_STORE_PASSWORD")
                .orElse(providers.provider { file("../keystore-password.txt").readText().trim() })
                .get()
            keyAlias = "qring"
            keyPassword = providers.gradleProperty("QRING_KEY_PASSWORD")
                .orElse(providers.provider { file("../keystore-password.txt").readText().trim() })
                .get()
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
