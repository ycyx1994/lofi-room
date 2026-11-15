plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lofiroom.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lofiroom.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // 占位签名便于侧载，发布前换正式密钥
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
