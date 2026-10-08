plugins { id("com.android.application") }

android {
    namespace = "com.primorye.weather"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.primorye.weather"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
}
