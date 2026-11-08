plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
// R137 · 签名材料不再进仓库：keystore 路径、store/key 密码、alias 全部由
// CI Secret 注入环境变量。缺任一项都不建 signingConfig（本地会落到默认 debug 签名），
// 由 CI 的「签名证书守门」比对 SHA-256 拦住，绝不把未签名或默认签名的包发出去。
val key = rootProject.file(System.getenv("TINGBEN_KEYSTORE") ?: "keystore.jks")
val storePass = System.getenv("TINGBEN_STORE_PASSWORD")
val keyPass = System.getenv("TINGBEN_KEY_PASSWORD")
// R146 · 局部变量不能叫 keyAlias：在 create("tingben") { } 这个带 receiver 的
// lambda 里，给同名字段赋值时右侧会优先解析成 SigningConfig 自己的属性
// （初始为 null），而不是外层局部变量 —— 结果自赋值成 null，
// packageDebug 报 "SigningConfig tingben is missing required property keyAlias"。
// 改名 signAlias，彻底避开同名遮蔽。
val signAlias = System.getenv("TINGBEN_KEY_ALIAS")
val signReady = key.exists() && !storePass.isNullOrBlank() &&
        !keyPass.isNullOrBlank() && !signAlias.isNullOrBlank()
val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull()
val appVersion = rootProject.file("version.txt").readText().trim()
    .ifBlank { error("version.txt 为空，不能确定版本号") }
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
        if (signReady) create("tingben") {
            storeFile = key
            storePassword = storePass
            keyAlias = signAlias
            keyPassword = keyPass
            enableV1Signing = true; enableV2Signing = true; enableV3Signing = false
        }
    }
    buildTypes {
        debug { if (signReady) signingConfig = signingConfigs.getByName("tingben") }
        release {
            if (signReady) signingConfig = signingConfigs.getByName("tingben")
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
