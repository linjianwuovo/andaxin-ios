pluginManagement {
    repositories {
        // 阿里云镜像排在前面：本机对海外直连不稳（用户级 gradle.properties 里那批注释掉的
        // 7897 代理就是这事），shared 这条 KMP 线的新东西——multiplatform 插件、
        // iosArm64 变体、kotlin-native-prebuilt 编译器——全指望从这儿下。直连那三家留作兜底。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "AndaXin"
include(":app")
include(":shared")
