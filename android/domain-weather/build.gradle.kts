plugins {
    id("com.android.library")
}

android {
    namespace = "dev.photopilot.domain.weather"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 29
    }
}
