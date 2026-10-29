plugins {
    id("com.android.application")
}

android {
    namespace = "com.boat.cineagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.boat.cineagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.1.8"

        buildConfigField("int", "UDP_DISCOVERY_PORT", "50000")
        buildConfigField("int", "TCP_COMMAND_PORT", "50001")
        buildConfigField("int", "PROTOCOL_VERSION", "1")
        buildConfigField("String", "FIRMWARE_VERSION", "\"1.0.4\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
