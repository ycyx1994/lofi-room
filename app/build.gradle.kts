import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 正式签名：keystore/lofiroom-release.jks + keystore.properties（各机器拷贝同一份，保证签名一致）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val releaseStoreFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore/lofiroom-release.jks"))

android {
    namespace = "com.lofiroom.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lofiroom.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 9
        versionName = "1.8"
    }

    signingConfigs {
        if (releaseStoreFile.exists()) {
            create("lofiroom") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias", "lofiroom")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 优先正式密钥；缺 keystore 的机器回退 debug 占位（签名与正式包不兼容，仅限本机自测）
            signingConfig = signingConfigs.findByName("lofiroom") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true // #compose-tv-migration P0
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")

    // #23 自定义中心：局域网 H5 管理页。NanoHTTPD 提供内嵌 HTTP 服务（multipart 上传），
    // zxing core 仅做 QR 编码（BitMatrix 自绘 Bitmap，不引 javase）。JSON 用平台自带 org.json。
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")

    // #compose-tv-migration P0 Compose for TV 工具链。
    // 版本组合守住 minSdk 21：androidx 2025 年中之后的产物普遍抬到 minSdk 23，
    // 故取 Compose 1.8.1（BOM 2025.05.00，minSdk 21）+ tv-material 1.0.1（minSdk 21，要求 Compose ≥1.6.8）
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.activity:activity:1.9.3") // ComponentActivity 提供 ViewTreeLifecycleOwner
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.tv:tv-material:1.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
