plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qring.print"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.qring.print"
        minSdk = 33          // Android 13+：BLE 新 API（旧 API 在 SDK34 编译时 HIDDEN）
        targetSdk = 34
        versionCode = 8
        versionName = "0.5.2"
    }

    // 正式签名（2026-08-11 生成 release.jks；密码在 android/keystore-password.txt）。
    // 必须先于 buildTypes 声明（buildTypes 引用 signingConfigs）
    signingConfigs {
        create("release") {
            storeFile = file("../release.jks")
            storePassword = providers.gradleProperty("QRING_STORE_PASSWORD")
                .orElse(providers.provider { file("../keystore-password.txt").readText().trim() })
                .get()
            keyAlias = "qring"
            keyPassword = providers.gradleProperty("QRING_KEY_PASSWORD")
                .orElse(providers.provider { file("../keystore-password.txt").readText().trim() })
                .get()
        }
    }

    buildTypes {
        release {
            // R8 瘦身（2026-08-12 开：dex 5.5MB 主体，v0.4.1 后无 minify）
            // zxing 自带 consumer rules；FileProvider 走 manifest 不受影响
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests {
            // Robolectric 需要真实资源（assets/icons、R 资源）
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // 条码/二维码生成（2026-08-11 加，参考 QrintPrint-Windows）
    implementation("com.google.zxing:core:3.5.3")
    // 协议/算法单元测试（2026-08-12 加：QringProtocol/Dither/Canny）
    testImplementation("junit:junit:4.13.2")
    // Robolectric 界面测试（2026-08-12 加：Activity 启动/切页/预览，JVM 本地跑）
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test:monitor:1.6.1")  // InstrumentationRegistry（core 的传递依赖，显式声明防丢）
}

// 单元测试执行（2026-08-12）：不用默认 test 任务——Gradle Test worker 的 @argfile
// 在 Windows 中文路径下 classpath 失效（转义后的 \\ 路径加载不到类），
// JavaExec 直接传 -cp 命令行参数绕开该问题。
// 用法：gradle runUnitTests
tasks.register<JavaExec>("runUnitTests") {
    group = "verification"
    description = "运行 JVM 单元测试（JUnitCore，绕开中文路径 worker 问题）"
    // JavaExec 不自动编译测试——先编译 test 源集
    dependsOn("compileDebugUnitTestKotlin")
    // debugUnitTestRuntimeClasspath 含 main 类输出 + junit 依赖；测试类目录 + android.jar 单独加
    // （AGP 8.5.2 在 Gradle 8.7 下 unitTest classpath 不带 android.jar，Robolectric 需要——2026-08-12 实测）
    val androidJar = files("${android.sdkDirectory.path}/platforms/android-${android.compileSdk}/android.jar")
    classpath = configurations["debugUnitTestRuntimeClasspath"] +
        files(layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")) +
        androidJar
    mainClass.set("org.junit.runner.JUnitCore")
    // JavaExec 不走 AGP 的 AAR transform 链路——classpath 里的 .aar 不会自动解出
    // classes.jar（NoClassDefFoundError: androidx.*）。doFirst 手动解压替换。
    // （2026-08-12 实测根因；robotolectric 的 android-all 镜像默认 maven central 太慢，
    //  已预下载到 ~/.robolectric 缓存，并指定阿里云镜像兜底）
    doFirst {
        val outDir = layout.buildDirectory.dir("testcp-aar").get().asFile.apply { mkdirs() }
        classpath = files(
            classpath.files.map { f ->
                if (f.isFile && f.name.endsWith(".aar")) {
                    val out = File(outDir, f.name.removeSuffix(".aar") + ".jar")
                    if (!out.exists()) {
                        copy {
                            from(zipTree(f)) { include("classes.jar") }
                            into(outDir)
                            rename { out.name }
                        }
                    }
                    out
                } else f
            }
        )
    }
    jvmArgs("-Drobolectric.dependency.repo.url=https://maven.aliyun.com/repository/public")
    args(
        "com.qring.print.QringProtocolTest",
        "com.qring.print.DitherTest",
        "com.qring.print.CannyTest",
        "com.qring.print.MainActivityUiTest",
    )
}
