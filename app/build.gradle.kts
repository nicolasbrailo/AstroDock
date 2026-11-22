plugins {
    alias(libs.plugins.android.application)
}

// A release is built before its tag exists: tools/build-apks.sh is run here,
// the APKs are uploaded, and GitHub creates the V<n> tag as the release is
// published. So the script works out the next number and passes it in
// (-PappVersion=17), and the version name is the bare number because the Apps
// tab strips the V from a tag when it compares the two, for releases without a
// digest. Any other build (Android Studio, a plain gradlew) is named after the
// newest tag it descends from, plus how far past it and -dirty for uncommitted
// changes (16-dev-3-gabc1234), so it can't pass for a release; it keeps that tag's
// version code, so it installs over that release without a downgrade.
data class AppVersion(val code: Int, val name: String)

val appVersion: AppVersion = run {
    val given = providers.gradleProperty("appVersion").orNull
    if (given != null) {
        val code = given.toIntOrNull()
        require(code != null && code > 0) { "appVersion must be a positive number, not \"$given\"" }
        return@run AppVersion(code, code.toString())
    }
    val described = providers.exec {
        commandLine("git", "describe", "--tags", "--match", "V[0-9]*", "--dirty")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
    val match = Regex("""^V(\d+)(.*)$""").find(described)
    if (match == null) {
        logger.warn("No V<n> tag found by git describe, building as version 1")
        AppVersion(1, "0-untagged")
    } else {
        val (number, rest) = match.destructured
        AppVersion(number.toInt(), "$number-dev$rest")
    }
}

android {
    namespace = "com.nicobrailo.astrodock"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.nicobrailo.astrodock"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersion.code
        versionName = appVersion.name

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // WebRTC is the only native code, and every Portal is arm64 (even the
        // Android 9 one), so the other three ABIs would only add 30 MB
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    testOptions {
        // android.util.Log etc. do nothing in unit tests instead of throwing
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.viewpager2)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.paho.mqtt)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.webrtc)
    implementation(project(":wakeword"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}