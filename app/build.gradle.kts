plugins {
    id("com.android.application")
}

// One version for the repository — see gradle.properties, and vitals/build.gradle.kts for the code.
val release = providers.gradleProperty("r99.version").get()

android {
    namespace = "uk.co.r99companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "uk.co.r99companion"
        minSdk = 26
        targetSdk = 36
        val (major, minor, patch) = release.split('.').map { it.toInt() }
        versionCode = major * 10_000 + minor * 100 + patch
        versionName = release
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    // The sleep record is pure bytes, so it can be checked on the JVM without a ring.
    testImplementation("junit:junit:4.13.2")
}
