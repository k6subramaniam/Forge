// :mobile — the phone app (Jetpack Compose, Material 3).
// applicationId matches :wear on purpose: Wearable Data Layer pairing requires
// phone and watch apps to share an applicationId (and signing key) — see README.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

android {
    namespace = "com.forge.mobile"
    // Health Connect 1.1.0 requires compiling against API 36. Compile-time only:
    // targetSdk (runtime behaviour) stays at 35.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.forge.fitness"
        minSdk = 26
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
    testOptions {
        unitTests {
            // Robolectric reads the merged manifest and resources.
            isIncludeAndroidResources = true
        }
    }
}

room {
    // Exported schemas are committed: every future version needs a migration
    // tested against the schema it migrates from.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.health.connect.client)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
