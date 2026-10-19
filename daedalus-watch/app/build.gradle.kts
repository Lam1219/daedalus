plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.medicina.daedaluswatch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.medicina.daedaluscompanion"
        minSdk = 30  // Wear OS 3+ (Watch5 Pro runs Wear OS 3/4)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Wear OS
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-input:1.2.0-alpha02")

    // Samsung Health Sensor SDK (SpO2, beat-to-beat intervals): drop the .aar into app/libs/
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // Health Services API (watch sensors: HR, HRV)
    implementation("androidx.health:health-services-client:1.0.0")

    // Wear OS Phone–Watch communication
    implementation("androidx.wear:wear-phone-interactions:1.1.0-alpha05")
    implementation("com.google.android.gms:play-services-wearable:18.1.0")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")

    // UI
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Unit tests (JVM)
    testImplementation("junit:junit:4.13.2")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
