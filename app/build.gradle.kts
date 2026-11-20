plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.voiceanswer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.voicephone"
        minSdk = 26
        targetSdk = 34
        versionCode = 25
        versionName = "3.22"
    }

    signingConfigs {
        create("release") {
            // Постоянный ключ подписи: одинаков при каждой сборке, поэтому обновления
            // ставятся поверх, а Android/Play Protect не видят каждый раз «нового автора».
            storeFile = file("release.jks")
            storePassword = "voiceanswer"
            keyAlias = "voiceanswer"
            keyPassword = "voiceanswer"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
