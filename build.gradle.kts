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
        // no-compatibility：接口默认方法只生成 JVM default，不为继承的 deprecated
        // 默认方法（如 StatusBarWidget.getPresentation(PlatformType)）生成兼容桥接，
        // 从而消除 Marketplace 验证器的 deprecated API 报告
        freeCompilerArgs.add("-jvm-default=no-compatibility")
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

// 将插件版本写入打包资源，供运行时读取（避免使用 @ApiStatus.Internal 的 PluginManager API）
val generateVersionResource = tasks.register("generateVersionResource") {
    val versionValue = providers.gradleProperty("pluginVersion")
    val outputDir = layout.buildDirectory.dir("generated/versionResource")
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("nexus-mcp-version.txt").asFile
        file.parentFile.mkdirs()
        file.writeText(versionValue.get())
    }
}

sourceSets {
    main {
        resources {
            srcDir(generateVersionResource)
        }
    }
}

// main 入口属于独立源集，避免标准 test 将它视为待发现的框架测试。
val contract = sourceSets.create("contract") {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
    // 独立 JVM 也需要通常由 Rider 提供的 Kotlin 等编译期依赖。
    runtimeClasspath += compileClasspath + sourceSets.main.get().runtimeClasspath
}
kotlin.target.compilations.getByName("contract").associateWith(kotlin.target.compilations.getByName("main"))

// 独立 JVM 契约检查，无需启动 Rider、UE 或引入测试框架。
val testProxyPolicy = tasks.register<JavaExec>("testProxyPolicy") {
    dependsOn(tasks.named(contract.classesTaskName))
    classpath = contract.runtimeClasspath
    mainClass.set("com.nexusmcp.mcp.ProxySessionPolicyTestKt")
}

tasks.named("check") {
    dependsOn(testProxyPolicy)
}

tasks {
    wrapper {
        gradleVersion = "9.0.0"
    }
}
