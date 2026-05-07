plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aicomp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aicomp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    // Compose disabled - using XML views only
    // buildFeatures { compose = false }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Material 3 for XML views (MaterialButton)
    implementation("com.google.android.material:material:1.12.0")
    
    // AndroidX Preference for settings
    implementation("androidx.preference:preference-ktx:1.2.1")
    
    // CameraX
    implementation("androidx.camera:camera-core:1.3.2")
    implementation("androidx.camera:camera-camera2:1.3.2")
    implementation("androidx.camera:camera-lifecycle:1.3.2")
    implementation("androidx.camera:camera-view:1.3.2")
    
    // ML Kit - Object Detection
    implementation("com.google.mlkit:object-detection:17.0.1")
    
    // ML Kit - Face Detection
    implementation("com.google.mlkit:face-detection:16.1.5")
    
    // ML Kit - Pose Detection
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    implementation("com.google.mlkit:pose-detection-accurate:18.0.0-beta5")
    
    // Accompanist for permissions
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Security - encrypted API key storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Markdown rendering for AI guidance panel
    implementation("io.noties.markwon:core:4.6.2")
    
    // EXIF Interface
    implementation("androidx.exifinterface:exifinterface:1.3.2")
    
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
