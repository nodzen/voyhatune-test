//import com.android.build.gradle.internal.dependency.isProguardRule

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ru.big.town.restoremode"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.big.town.restoremode"
        minSdk = 30
        targetSdk = 36
        versionCode = 30903
        versionName = "3.9.0-rc3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            // Release packages are installed on the head unit; run R8 here, not only in debug.
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")

        }
        debug {
            // Keep diagnostics and local iteration debuggable. Size optimization belongs to
            // release, where the Full packaging checks exercise the output.
            isMinifyEnabled = false
            isShrinkResources = false

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")

        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }
    ndkVersion = "29.0.14206865"

}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
