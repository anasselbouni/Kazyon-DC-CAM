plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}
val camerax_version = "1.3.3"
android {
    namespace = "ma.kazyon.dccam"
    compileSdk = 36

    buildFeatures {
        dataBinding = true
    }
    defaultConfig {
        applicationId = "ma.kazyon.dccam"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.250825"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.exifinterface:exifinterface:1.3.6") // Use the latest stable version// For SMB communication
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.exifinterface:exifinterface:1.3.6")// For Kotlin Coroutines

    // CameraX dependencies, using the defined variable with Kotlin DSL string interpolation
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-video:${camerax_version}")
    // If you plan to add video features
        implementation("androidx.camera:camera-view:${camerax_version}")
    // Essential for PreviewView
        implementation("androidx.camera:camera-extensions:${camerax_version}")
    // For advanced features like portrait mode

    // For SMB communication
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.9")

    // For Kotlin Coroutines (you had two versions, corrected to one set)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}