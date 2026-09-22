plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ru.big.town.anative"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.big.town.anative"
        minSdk = 30
        targetSdk = 36
        versionCode = 30902
        versionName = "3.9.0-rc2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    ndkVersion = "29.0.14206865"
    buildToolsVersion = "36.0.0"
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.legacy.support.v4)
    implementation(libs.legacy.support.v13)
    // android.car is supplied by the AAOS/OEM system image; the local jar is compile-time only.
    compileOnly(files("lib/android.car.jar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

}
