plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.nexusmcp"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        localPlatformArtifacts()
    }
}

dependencies {
    intellijPlatform {
        rider(providers.gradleProperty("platformVersion"), useInstaller = false)
        // 不再依赖内置 MCP Server：bundledPlugin("com.intellij.mcpServer")
    }
    // Netty：Rider 宿主已内置，编译期引用即可；打包时不捆绑，避免双份 ClassLoader 冲突
    compileOnly("io.netty:netty-all:4.1.119.Final")
    // Java-WebSocket 客户端（与 UE 通信）
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    // JSON 处理
    implementation("org.json:json:20240303")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.byteyang.nexusmcp"
        name = "Nexus MCP"
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "253"
            untilBuild = "263.*"
        }
    }

    buildSearchableOptions = false
}

tasks {
    wrapper {
        gradleVersion = "9.0.0"
    }
}
