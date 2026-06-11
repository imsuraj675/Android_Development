import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
//    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.sender"
    compileSdk {
        version = release(36)
    }

    // Load keystore credentials from keystore.properties (not committed to VCS).
    // If the file is absent the release build is unsigned — fine for local debug APKs,
    // but every distributed release MUST be signed with the same key so Android allows
    // installing updates over an existing app.
    val keystoreProps = Properties()
    val keystoreFile = rootProject.file("keystore.properties")
    val hasKeystore = keystoreFile.exists() &&
        keystoreFile.readText().contains("YOUR_STORE_PASSWORD").not()
    if (keystoreFile.exists()) keystoreProps.load(keystoreFile.inputStream())

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile     = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias      = keystoreProps["keyAlias"] as String
                keyPassword   = keystoreProps["keyPassword"] as String
            }
        }
    }

    // versionCode is derived automatically from the total git commit count —
    // it increments with every commit so you never need to touch it manually.
    // Falls back to 1 if git is unavailable (e.g. fresh checkout with no history).
    val gitCommitCount = try {
        val out = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .directory(rootProject.projectDir)
            .start()
            .inputStream.bufferedReader().readText().trim()
        out.toIntOrNull() ?: 1
    } catch (_: Exception) { 1 }

    defaultConfig {
        applicationId = "com.example.sender"
        minSdk = 34
        targetSdk = 36
        // versionCode auto-increments via git commit count above.
        // versionName: bump manually when you cut a meaningful release (1.1.0 → 1.2.0 etc.).
        versionCode = gitCommitCount
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("long", "TRUST_DURATION_DAYS", "30L")
    }

    buildTypes {
        release {
            if (hasKeystore) signingConfig = signingConfigs.getByName("release")
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
//    kotlinOptions {
//        jvmTarget = "11"
//    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.jmdns)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
