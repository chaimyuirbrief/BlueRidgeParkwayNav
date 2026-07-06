import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Resolve the Google Maps API key from (in priority order):
//   1. The MAPS_API_KEY environment variable (e.g. a GitHub Actions secret named
//      maps_api_key exposed as env: MAPS_API_KEY: ${{ secrets.maps_api_key }})
//   2. A `MAPS_API_KEY` entry in local.properties (for local development)
// Falling back to an empty string so the project still configures without a key.
val localProps: Properties = Properties().apply {
    val lp = rootProject.file("local.properties")
    if (lp.exists()) lp.inputStream().use { load(it) }
}
fun secret(vararg names: String): String {
    for (n in names) System.getenv(n)?.takeIf { it.isNotBlank() }?.let { return it }
    for (n in names) localProps.getProperty(n)?.takeIf { it.isNotBlank() }?.let { return it }
    return ""
}

// SDK key (Maps SDK for Android + Places) — this one may carry an "Android apps" restriction.
val mapsApiKey: String = secret("MAPS_API_KEY", "maps_api_key")
// Directions web-service key — must NOT be Android-restricted (web services reject that).
// Falls back to the SDK key so a single-key setup still works if this isn't provided.
val directionsApiKey: String =
    secret("DIRECTIONS_API_KEY", "directions_api_key").ifBlank { mapsApiKey }
// National Park Service Data API key (free) for live Blue Ridge Parkway road closures.
val npsApiKey: String = secret("NPS_API_KEY", "nps_api_key")

android {
    namespace = "com.blueridge.parkwaynav"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blueridge.parkwaynav"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "0.1.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Injected into AndroidManifest as ${MAPS_API_KEY} and exposed in code as
        // BuildConfig.MAPS_API_KEY (needed for the Places SDK initialization).
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        buildConfigField("String", "DIRECTIONS_API_KEY", "\"$directionsApiKey\"")
        buildConfigField("String", "NPS_API_KEY", "\"$npsApiKey\"")
    }

    signingConfigs {
        // Committed fixed debug keystore so the signing SHA-1 is stable across all builds
        // (local + CI). Required for a Google Maps key with an "Android apps" restriction.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Maps & location
    implementation("com.google.maps.android:maps-compose:4.4.1")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.libraries.places:places:3.5.0")

    // Persistence & serialization
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
