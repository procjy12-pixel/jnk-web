plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.co.jnkcorp.filter"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.co.jnkcorp.filter"
        minSdk = 29
        targetSdk = 34
        versionCode = 12
        versionName = "0.7.1"
        // 폰(ARM)용만 넣어 용량을 줄임 (얼굴 인식 부품이 기기 종류마다 8MB 쯤)
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 서명 키를 만들기 전까지는 디버그 키로 서명해 바로 설치해 볼 수 있게 둡니다.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // 앱 카메라 (CameraX) — 화면은 우리가 그리고, 카메라 제어만 씁니다
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // 잡티 찾을 때 눈·눈썹·코·입을 피하려고 얼굴 윤곽 인식 (모델 포함, 인터넷 불필요)
    implementation("com.google.mlkit:face-detection:16.1.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
