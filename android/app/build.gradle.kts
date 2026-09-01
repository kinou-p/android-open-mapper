import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        load(localFile.inputStream())
    }
}

val keystoreStorePassword = System.getenv("KEYSTORE_PASSWORD")
    ?: localProperties.getProperty("KEYSTORE_PASSWORD")
    ?: error("KEYSTORE_PASSWORD manquant : définir via variable d'environnement ou local.properties")

val keystoreKeyPassword = System.getenv("KEY_PASSWORD")
    ?: localProperties.getProperty("KEY_PASSWORD")
    ?: keystoreStorePassword

val keystoreAlias = System.getenv("KEY_ALIAS")
    ?: localProperties.getProperty("KEY_ALIAS")
    ?: "openmapper"

// Secret partagé avec le backend Cloudflare (wrangler secret put APP_SECRET) pour signer
// les requêtes POST (HMAC-SHA256). Fourni via APP_SECRET (env ou local.properties), jamais en dur.
val apiSigningSecret = System.getenv("APP_SECRET")
    ?: localProperties.getProperty("APP_SECRET")
    ?: error("APP_SECRET manquant : définir via variable d'environnement ou local.properties")

android {
    namespace = "com.kinou.gameassist"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kinou.gameassist"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.1.4"

        buildConfigField("String", "API_SIGNING_SECRET", "\"$apiSigningSecret\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_PATH") ?: localProperties.getProperty("KEYSTORE_PATH") ?: "keystore/openmapper.keystore")
            storePassword = keystoreStorePassword
            keyAlias = keystoreAlias
            keyPassword = keystoreKeyPassword
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
        aidl = true
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku API (Elevated ADB/Root system privileges without PC)
    val shizukuVersion = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizukuVersion")
    implementation("dev.rikka.shizuku:provider:$shizukuVersion")

    // JSON Serialization
    implementation("com.google.code.gson:gson:2.11.0")

    // EncryptedSharedPreferences (stockage chiffré du token appareil)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
