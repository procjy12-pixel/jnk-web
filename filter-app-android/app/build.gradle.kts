plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "kr.co.jnkcorp.filter"
    compileSdk = 34

    defaultConfig {
        applicationId = "kr.co.jnkcorp.filter"
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"
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
}

dependencies {
    // 앱 카메라 (CameraX) — 화면은 우리가 그리고, 카메라 제어만 씁니다
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.activity:activity-ktx:1.8.2")
    testImplementation("junit:junit:4.13.2")
}
