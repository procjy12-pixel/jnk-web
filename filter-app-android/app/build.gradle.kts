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
        versionCode = 5
        versionName = "0.5"
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
    testImplementation("junit:junit:4.13.2")
}
