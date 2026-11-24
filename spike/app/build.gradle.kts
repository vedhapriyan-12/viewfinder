plugins {
    id("com.android.application")
}

android {
    namespace = "app.viewfinder.spike"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.viewfinder.spike"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
