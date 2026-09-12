plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phonetype.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonetype.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") {
        // zxing core 3.4+ 只在 java 8 目标下可用，embedded 库可正常降级使用
        exclude(group = "com.google.zxing", module = "core")
    }
    implementation("com.google.zxing:core:3.5.3")
}
