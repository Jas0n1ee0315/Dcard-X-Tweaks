plugins {
    id("com.android.application")
}

android {
    namespace = "com.jasonlee.dcardxtweaks"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jasonlee.dcardxtweaks"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.luckypray:dexkit:2.0.7")
}
