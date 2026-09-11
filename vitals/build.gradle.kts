plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// See gradle.properties: one version for the repository, and where its releases live.
val release = providers.gradleProperty("r99.version").get()
val developer = providers.gradleProperty("r99.developer").get()
val repo = providers.gradleProperty("r99.repo").get()

android {
    namespace = "uk.co.r99vitals"
    compileSdk = 37

    defaultConfig {
        applicationId = "uk.co.r99vitals"
        minSdk = 26
        targetSdk = 36
        // 0.2.0 is 200 and 1.4.12 is 10412, so every release installs over the last. Minor and
        // patch stay under a hundred.
        val (major, minor, patch) = release.split('.').map { it.toInt() }
        versionCode = major * 10_000 + minor * 100 + patch
        versionName = release
        buildConfigField("String", "DEVELOPER", "\"$developer\"")
        buildConfigField("String", "REPO", "\"$repo\"")
    }

    signingConfigs {
        // CI is handed this PC's debug key as a file (see .github/workflows/build.yml), so what it
        // builds installs over what was installed from here. Without it, the usual
        // ~/.android/debug.keystore.
        getByName("debug") {
            providers.environmentVariable("R99_KEYSTORE").orNull?.let { storeFile = file(it) }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
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

}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")

    val compose = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(compose)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Local, on-device only: Health Connect is IPC, not a network service. The one thing Vitals
    // asks the network is whether a newer release is out — see Updates.kt.
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Ring speaks in frames, which are pure bytes and so testable on the JVM without a ring.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
