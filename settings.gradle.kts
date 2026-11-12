pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // 注意：本机 ~/.gradle/init.d/mirror.gradle 会注入阿里云镜像仓库，
    // 因此不能使用 FAIL_ON_PROJECT_REPOS。
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LofiRoom"
include(":app")
