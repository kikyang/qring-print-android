// 根构建脚本
plugins {
    // AGP 8.2.2 → 8.5.2（2026-08-12：8.2.2 在 Gradle 8.7 下 unit test 类加载失败，8.5 官方支持 Gradle 8.7）
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
