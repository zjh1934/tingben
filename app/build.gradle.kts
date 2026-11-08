plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val key = rootProject.file("keystore.jks")
val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull()
val appVersion = rootProject.file("version.txt").readText().trim().ifBlank { "1.1.2" }
android {
    namespace = "com.tingben.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tingben.app"
        minSdk = 26
        targetSdk = 35
        versionCode = if (buildNumber != null) 100 + buildNumber else 1
        versionName = appVersion
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        if (key.exists()) create("tingben") {
            storeFile = key
            storePassword = "motingdebug"
            keyAlias = "moting"
            keyPassword = "motingdebug"
            enableV1Signing = true; enableV2Signing = true; enableV3Signing = false
        }
    }
    buildTypes {
        debug { if (key.exists()) signingConfig = signingConfigs.getByName("tingben") }
        release {
            if (key.exists()) signingConfig = signingConfigs.getByName("tingben")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    val ready = file("src/main/jniLibs/arm64-v8a/libllama.so").exists() &&
                file("src/main/jniLibs/arm64-v8a/libqwen.so").exists()
    if (ready) externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
