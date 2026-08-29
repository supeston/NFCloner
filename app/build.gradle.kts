plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mifare.cloner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mifare.cloner"
        minSdk = 24
        targetSdk = 35
        versionCode = 15
        versionName = "1.2.8"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("sharedSigning") {
            storeFile = file("release.keystore")
            storePassword = "irblasterpass"
            keyAlias = "irblaster"
            keyPassword = "irblasterpass"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sharedSigning")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sharedSigning")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register("copyApkToDesktop") {
    dependsOn("assembleRelease")
    doLast {
        val desktop = File(System.getProperty("user.home"), "Desktop")
        val releaseApk = File(project.buildDir, "outputs/apk/release/app-release.apk")
        val altReleaseApk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val sourceApk = if (releaseApk.exists()) releaseApk else altReleaseApk
        if (sourceApk.exists()) {
            val target = File(desktop, "NFCloner.apk")
            sourceApk.copyTo(target, overwrite = true)
            println("SUCCESS_COPIED_TO_DESKTOP: " + target.absolutePath + " (" + target.length() + " bytes)")
        } else {
            println("SOURCE_APK_NOT_FOUND: checked " + releaseApk.absolutePath + " and " + altReleaseApk.absolutePath)
        }
    }
}
