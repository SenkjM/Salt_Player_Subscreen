// 本仓库为独立 Git 仓库，构建由 spw-workshop-api 仓库统一驱动：
// 在其 settings.gradle.kts 中 include(":Salt_Player_Subscreen") 并把 projectDir 指向本目录。
// 需要 JDK 25，命令：./gradlew :Salt_Player_Subscreen:plugin

import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java-library")
    id("org.jetbrains.kotlin.kapt")
    alias(libs.plugins.jetbrains.kotlin.jvm)
    id("com.xuncorp.spw.workshop")
}

group = "com.senkj.spwsidescreen"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)

    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))

    project(":api").let {
        compileOnly(it)
        kapt(it)
    }
}

spmod {
    PluginClass = "com.senkj.spwsidescreen.MainPlugin"
    PluginId = "com.senkj.spwsidescreen"
    PluginName = "副屏播放界面"
    PluginDescription = "在指定显示器上自绘播放界面，支持 640x340 / 320x170 低分辨率适配与逐字歌词，宿主可保持托盘常驻"
    PluginVersion = "0.1.0"
    PluginProvider = "SenkjM"
    PluginHasConfig = true
    PluginOpenSourceUrl = "https://github.com/SenkjM/Salt_Player_Subscreen"
    PluginPermissions = listOf(PluginPermission.LIBRARY_READ)
}
