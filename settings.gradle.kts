// 仓库顺序按环境切换：本地（国内）靠阿里云镜像加速，CI（GitHub Actions 的
// runner 在海外）必须走官方源。
//
// 踩过的坑：镜像优先时，runner 请求阿里云拿到 404，Gradle 就记为「该仓库没有
// 这个包」；而 ksp 的插件标记在官方源上才有完好的路径，于是配置阶段直接报
// "Plugin [id: 'com.google.devtools.ksp', version: '2.3.13'] was not found"。
// 本地因为 Gradle 缓存里早就有这个包，一直没暴露。
//
// 判断必须写在各自的块里：pluginManagement / dependencyResolutionManagement 是
// Kotlin DSL 的特殊块（在脚本体之前单独编译执行），读不到脚本顶部声明的 val。

pluginManagement {
    // GitHub Actions 会自动注入 CI=true
    val onCi = !System.getenv("CI").isNullOrBlank()

    repositories {
        if (!onCi) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
        if (onCi) {
            // 官方源万一抽风，镜像仍然兜底一次
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/central")
        }
    }
}

dependencyResolutionManagement {
    val onCi = !System.getenv("CI").isNullOrBlank()

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!onCi) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
        if (onCi) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
        }
    }
}

rootProject.name = "FitGym"
include(":app")
