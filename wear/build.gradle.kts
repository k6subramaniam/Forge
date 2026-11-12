// :wear — the watch app (Compose for Wear OS + Horologist).
// applicationId matches :mobile on purpose: the Wearable Data Layer only pairs
// apps that share an applicationId (and signing key) — see README.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.forge.wear"
    compileSdk = 36 // kept in lockstep with :mobile; targetSdk stays at 35

    defaultConfig {
        applicationId = "com.forge.fitness"
        minSdk = 30 // Wear OS 3 and later
        targetSdk = 35
        // CI passes its run number, so every build is newer than the last and
        // installs as an update. Local builds default to 1.
        versionCode = providers.environmentVariable("FORGE_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = "0.1.0"
    }

    // Release signing: one permanent key for phone AND watch. The Wearable Data
    // Layer pairs apps only when applicationId and signing key match, and Android
    // only installs an update signed with the same key. The key never lives in
    // the repo: CI decodes it from GitHub secrets (see README "Release signing");
    // locally, export the same three variables. Without them, release builds come
    // out unsigned and debug builds are unaffected.
    val releaseKeystore = providers.environmentVariable("FORGE_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("FORGE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("FORGE_KEY_ALIAS").get()
                keyPassword = storePassword // PKCS12: one password for store and key
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Wearable Data Layer: today's plans go to the watch, finished sessions come back.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.horologist.compose.layout)
}
