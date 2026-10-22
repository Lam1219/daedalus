import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Supabase project settings live in supabase.properties (not committed; see supabase.properties.example).
val supabase = Properties().apply {
    val f = rootProject.file("supabase.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.medicina.daedaluscompanion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.medicina.daedaluscompanion"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Ship only the languages the app is translated into.
        resourceConfigurations += listOf("en", "fr")

        buildConfigField("String", "SUPABASE_URL", "\"${supabase.getProperty("SUPABASE_URL", "")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${supabase.getProperty("SUPABASE_ANON_KEY", "")}\"")
        // Deep link for email confirmation and password-reset links.
        manifestPlaceholders["authScheme"] = "daedalus"
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    // AppCompat: per-app language switching (AppCompatDelegate.setApplicationLocales)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Sign-in: encrypted session store
    implementation("androidx.security:security-crypto:1.0.0")

    // Material (for themes)
    implementation("com.google.android.material:material:1.12.0")

    // Wear OS DataLayer
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // BLE
    implementation("no.nordicsemi.android:ble:2.7.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // JVM unit tests
    testImplementation("junit:junit:4.13.2")
}
