plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.okmap"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.okmap"
        minSdk = 24
        targetSdk = 34
        // CI passes -Pvcode=<run number>, so every new build re-extracts the map data
        versionCode = (project.findProperty("vcode") as String?)?.toIntOrNull() ?: 1
        versionName = "3.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // keep the big tile database uncompressed inside the APK (fast copy on first launch)
    androidResources {
        noCompress += listOf("mbtiles", "db")
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
