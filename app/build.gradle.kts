import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.agp.app)
}

android {
    namespace = "dev.xuanran.miglasses"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "dev.xuanran.miglasses"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs["debug"]
        }
    }

    packaging.resources.merges += "META-INF/xposed/*"
    lint { abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin.compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.libxposed.service)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.dexkit)
}
