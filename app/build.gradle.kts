import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Release 签名材料在仓库根目录的 keystore.properties 里（已 gitignore）。
 *
 * 文件不存在时**不阻断构建**：`assembleRelease` 会产出一个未签名的 APK，
 * 这样别人 clone 下来、以及没配 secrets 的 CI，照样能编译出包来验证代码。
 * 要分发的包必须签名，所以本地和正式 CI 都要补齐这个文件。
 *
 * 为什么不把密码写进版本库：仓库是公开的 AGPL 项目，
 * 密钥库一旦进了提交历史就再也拿不回来了。
 */
val keystoreProperties = Properties().apply {
    // 用 providers.fileContents 而不是直接读文件：它会把「这个文件的当前内容」登记成
    // 配置输入，改了密码之后下一次构建会自动重新配置。直接 File.readText 的话，
    // configuration cache 会一直沿用首次配置时的旧签名参数，排查起来非常费劲。
    providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
        .asText.orNull
        ?.let { load(it.reader()) }
}
val hasSigningKey = !keystoreProperties.getProperty("storeFile").isNullOrBlank()

android {
    namespace = "io.github.kylin33.aifitgym"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.github.kylin33.aifitgym"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("release") {
                // 路径相对仓库根目录 —— 绝对路径会把某台机器的目录结构写进构建脚本
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // v2/v3 是 Android 7+ 的校验方式，v1 留着兼容老设备，代价只有几十 KB
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8：类名混淆 + 未引用代码裁剪。26MB 里去掉大头是 Compose / Room / 协程的
            // 调试符号与泛型元数据，dex 部分通常能砍掉三分之一以上
            isMinifyEnabled = true
            // 连资源表一起裁：没被引用的 drawable / string / 布局会被删。
            // assets/ 里的动作库和图片不受影响 —— assets 不是资源，不参与 shrink
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }

        debug {
            // 包名加后缀，让 debug 和 release 能在同一台手机上共存：
            // 两者的签名必然不同，不加后缀就只能「卸了装、装了卸」，
            // 而改签名正是为了不再被迫卸载 —— 这条是配套的
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        // 演示短片必须保持未压缩：ExoPlayer 走 AssetDataSource 时要 openFd()，
        // 被压缩的 asset 拿不到文件描述符，只能退化成流式读，还多占一份内存
        noCompress += listOf("mp4")
    }
}

// Room 生成的 schema 落到 app/schemas，方便以后写迁移
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)

    // 动作演示短片。ExoPlayer 直接认 asset:///clips/x.mp4，不用先落磁盘
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    testImplementation(libs.junit)
}
