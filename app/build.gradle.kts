plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hosira.oopznative"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hosira.oopznative"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.12.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("local") {
            storeFile = file("../release.keystore")
            storePassword = "oopznative"
            keyAlias = "oopz"
            keyPassword = "oopznative"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("local")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources.excludes += "/META-INF/*" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 腾讯 TRTC 原生 SDK（语音房间、3A 降噪、设备路由）
    implementation("com.tencent.liteav:LiteAVSDK_TRTC:13.6.0.20747")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
