pluginManagement {
    val useChinaMirrors = providers.gradleProperty("useChinaMirrors")
        .orNull
        ?.toBooleanStrictOrNull()
        ?: true
    repositories {
        // 国内开发默认优先镜像；CI 传 -PuseChinaMirrors=false，避免镜像故障阻断发布。
        if (useChinaMirrors) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    val useChinaMirrors = providers.gradleProperty("useChinaMirrors")
        .orNull
        ?.toBooleanStrictOrNull()
        ?: true
    repositories {
        if (useChinaMirrors) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        mavenCentral()
        google()
    }
}

rootProject.name = "YunX-Desktop"
include(":desktop")
