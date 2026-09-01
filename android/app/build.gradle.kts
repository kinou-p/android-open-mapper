import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) {
        load(localFile.inputStream())
    }
}

val keystoreStorePassword = System.getenv("KEYSTORE_PASSWORD")
    ?: localProperties.getProperty("KEYSTORE_PASSWORD")
    ?: ""

val keystoreKeyPassword = System.getenv("KEY_PASSWORD")
    ?: localProperties.getProperty("KEY_PASSWORD")
    ?: keystoreStorePassword

val keystoreAlias = System.getenv("KEY_ALIAS")
    ?: localProperties.getProperty("KEY_ALIAS")
    ?: "openmapper"

// Secret partagé avec le backend Cloudflare (wrangler secret put APP_SECRET).
// Fourni via APP_SECRET (env ou local.properties), jamais en dur dans le VCS.
// ATTENTION — modèle de confiance : cette valeur est embarquée dans l'APK et donc
// extractible par décompilation. Elle ne fournit qu'une intégrité anti-falsification
// (HMAC-SHA256), PAS une authentification. L'anti-abus réel repose sur les rate-limits
// serveur et le jeton d'appareil opaque émis par /api/device/register.
val rawAppSecret = System.getenv("APP_SECRET")
    ?: localProperties.getProperty("APP_SECRET")
val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

val apiSigningSecret = when {
    !rawAppSecret.isNullOrBlank() -> rawAppSecret
    isReleaseTask -> throw GradleException(
        "❌ ERREUR CRITIQUE : La variable APP_SECRET (variable d'environnement ou dans local.properties) doit être définie pour compiler en mode Release !"
    )
    else -> "development_fallback_secret_key"
}

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
            val ksPath = System.getenv("KEYSTORE_PATH") ?: localProperties.getProperty("KEYSTORE_PATH") ?: "keystore/openmapper.keystore"
            val ksFile = file(ksPath)
            if (ksFile.exists() && keystoreStorePassword.isNotEmpty()) {
                storeFile = ksFile
                storePassword = keystoreStorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true && !releaseSigning.storePassword.isNullOrEmpty()) {
                signingConfig = releaseSigning
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile?.exists() == true && !releaseSigning.storePassword.isNullOrEmpty()) {
                signingConfig = releaseSigning
            }
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
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

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
