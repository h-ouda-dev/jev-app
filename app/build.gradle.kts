plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.heron"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.heron"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        // The S25 Ultra (and every modern phone) is arm64; x86_64 keeps the emulator working.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        // Where the app downloads its model from: the GitHub release published by .github/workflows/model.yml.
        buildConfigField("String", "MODEL_BASE_URL", "\"https://github.com/h-ouda-dev/jev-app/releases/download/model-v1\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // "full": everything, including importing past bank SMS (sideload / testing, and Play once
    // Google approves the SMS permission declaration for money management).
    // "play": no READ_SMS permission, so it can be submitted before that approval.
    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            isDefault = true
            buildConfigField("boolean", "SMS_IMPORT", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SMS_IMPORT", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so a release APK can be sideloaded; use your own key for Play.
            signingConfig = signingConfigs.getByName("debug")
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
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.onnxruntime.android)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
}
