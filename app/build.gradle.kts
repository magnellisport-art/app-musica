plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.marco.musica"
    compileSdk = 34

    defaultConfig {
        applicationId = "it.marco.musica"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "2.0.4"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.guava:guava:33.2.1-android")
}
