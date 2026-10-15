import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("com.google.devtools.ksp")
}

val roomVersion = "2.8.5"
val smbjVersion = "0.15.0"

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningReady = keystorePropertiesFile.isFile &&
    keystoreProperties.getProperty("storeFile") != null

dependencies {
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    implementation("com.hierynomus:smbj:$smbjVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "dev.photopilot.scaffold"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.photopilot.scaffold"
        minSdk = 29
        targetSdk = 36
        versionCode = 18
        versionName = "1.1.16"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.configureEach {
    if (name == "packageRelease" && !releaseSigningReady) {
        doFirst {
            throw GradleException(
                "release 构建需要 android/keystore.properties；请先生成签名 keystore 并写入该文件",
            )
        }
    }
}
