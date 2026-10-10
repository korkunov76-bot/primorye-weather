plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.primorye.weather"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.primorye.weather"
        minSdk = 24
        targetSdk = 35
        // номер версии растёт с каждой сборкой в GitHub Actions
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Постоянный ключ подписи: тогда новая сборка ставится поверх старой без удаления.
    // Если файла app/debug.keystore нет, сборка идёт как раньше (с временным ключом).
    val keystoreFile = file("debug.keystore")
    if (keystoreFile.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes { release { isMinifyEnabled = false } }
}
