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

// 缺正式密钥时 fail-fast：release 一旦回退 debug 签名，产出的包与原包签名不一致，
// 用户端更新必然撞 INSTALL_FAILED_UPDATE_INCOMPATIBLE（且只在装包那一刻才暴露，比构建失败贵得多）。
// 只按当前请求的任务名判断，不误伤 assembleDebug / test / lint 等纯 debug 流程；
// 聚合任务（build/assemble/bundle）会顺带构建 release 变体，一并纳入判断。
val aggregateTasksIncludingRelease = setOf("build", "assemble", "bundle", "publish")
val wantsReleaseTask = gradle.startParameter.taskNames.any { name ->
    name.contains("Release", ignoreCase = true) || name.substringAfterLast(':') in aggregateTasksIncludingRelease
}
if (wantsReleaseTask && !releaseStoreFile.exists()) {
    throw GradleException(
        "release 构建需要正式密钥：keystore.properties + keystore/lofiroom-release.jks（缺失会产出签名不一致的包）"
    )
}

android {
    namespace = "com.lofiroom.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lofiroom.tv"
        minSdk = 21
        targetSdk = 34
        versionCode = 14
        versionName = "1.9.4"
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
            // R8 代码裁剪 + 资源压缩：APK 33MB 里 dex 未压缩 20.6MB，几乎全是 Compose/media3/zxing
            // 未被引用到的部分（Compose 调试工具、media3 其它 container、zxing 的 javase 分支）。
            // 不裁剪时 proguardFiles 只是空转；开启后靠 proguard-rules.pro 的 keep 规则兜住反射入口。
            isMinifyEnabled = true
            isShrinkResources = true
            // 优先正式密钥；缺 keystore 的机器回退 debug 占位（签名与正式包不兼容，仅限本机自测）。
            // 正常路径上缺失密钥已在文件顶部抛异常，这里保留回退只为 IDE 单变体/工具链直连等
            // 拿不到 taskNames 的场景不至于配置期就崩，但会打警告，避免静默出「假正式包」。
            val releaseSigning = signingConfigs.findByName("lofiroom")
            if (releaseSigning == null) {
                project.logger.warn(
                    "⚠️ 未找到正式密钥（keystore.properties + keystore/lofiroom-release.jks），" +
                        "release 变体将回退 debug 签名 —— 仅限本机自测，禁止分发"
                )
            }
            signingConfig = releaseSigning ?: signingConfigs.getByName("debug")
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
        // media3 的 audio sink / renderer 扩展点在 1.4.1 里标了 @UnstableApi，工程按住它的 API
        // 就得显式 opt-in；不声明则 lint 的 UnsafeOptInUsageError 会把构建判红（22 个错误全出自这里）。
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }

    lint {
        // lint 进 CI 门禁（release.yml 的 lintRelease step），这里定死基线口径：
        // 真有 error 就拦住构建，而不是静默降级成 warning。
        abortOnError = true
        checkReleaseBuilds = true
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

    // 更新机制纯逻辑单测（UpdateChecker 版本解析/比较 + UpdateScheduler 节流退避）
    testImplementation("junit:junit:4.13.2")
}

// ── 构建卫生：自动清理 iCloud 同步在 build/ 里生成的重复副本 ──────────────────
// 项目若位于 iCloud 同步盘（如 ~/Documents/code），iCloud 会给 AGP 产物做 "NAME N.ext" /
// "NAME copy.ext" 数字后缀副本；文件名含空格会让 AGP 资源解析（parse*LocalResources）判为非法
// 文件名而构建失败。任务输入快照先于 doFirst，事后删除会留悬引用报 "not a file nor directory"，
// 故在**配置期**（任务输入快照前）删掉 build/ 下这类副本（只碰 build/ 不动源码）。
run {
    val dir = layout.buildDirectory.get().asFile
    if (dir.exists()) {
        val dupe = Regex(".* [0-9]+\\..+")
        fileTree(dir).forEach { f ->
            if (dupe.matches(f.name) || f.name.contains(" copy.")) f.delete()
        }
    }
}
