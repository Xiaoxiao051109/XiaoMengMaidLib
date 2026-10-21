plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // 只做「释放文件 + 注册服务 + 纯 Java 棋类实现」，不需要 NMS / paperweight
    compileOnly("io.papermc.paper:paper-api:${property("paperApiVersion")}")
    implementation(project(":tlm-folia-api"))
}

// 在项目作用域取值：processResources 块内的 property() 会解析到任务自身属性
val pluginProps = mapOf(
    "version" to project.version.toString(),
    "apiVersion" to project.property("mcVersion").toString()
)

tasks.processResources {
    val props = pluginProps
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.jar {
    archiveBaseName = "XiaoMengMaidLib"
    // Paper 插件不会自动加载 implementation 依赖，把 :api 的类并进插件 jar（单点携带）
    val apiJar = project(":tlm-folia-api").tasks.named<Jar>("jar")
    dependsOn(apiJar)
    from(apiJar.map { zipTree(it.archiveFile) }) { exclude("META-INF/**") }
}

/*
 * 资产管线：原模组源码树 → CraftEngine 内容包 / BetterModel 模型 / 运行时清单。
 * 全部为构建期产物，源模组路径只读，可用 -PtlmSource / -PtlmSourceRoot 覆盖。
 */
val tlmPackDir = providers.gradleProperty("tlmSource").map(rootProject::file)
val tlmSourceRoot = providers.gradleProperty("tlmSourceRoot").map(rootProject::file)
val convertedModelsDir = layout.buildDirectory.dir("generated/models")
val generatedContentDir = layout.buildDirectory.dir("generated/content")

val converterRuntime = configurations.create("converterRuntime")
dependencies {
    converterRuntime(project(":converter"))
}

val convertModels = tasks.register<JavaExec>("convertModels") {
    group = "tlm"
    description = "把 TLM 默认模型包转换为 BetterModel 的 .bbmodel"
    classpath = converterRuntime
    mainClass = "io.github.tlm.converter.convert.ModelPackConverter"
    val input = file(tlmPackDir.get())
    val output = convertedModelsDir.get().asFile
    inputs.dir(input)
    outputs.dir(output)
    args(input.absolutePath, output.absolutePath)
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    isIgnoreExitValue = true
}

val generateContent = tasks.register<JavaExec>("generateContent") {
    group = "tlm"
    description = "从原模组资源生成 CraftEngine 内容包"
    classpath = converterRuntime
    mainClass = "io.github.tlm.converter.content.ContentPackGenerator"
    val root = file(tlmSourceRoot.get())
    val assets = root.resolve("main/resources/assets/touhou_little_maid")
    val data = root.resolve("generated/resources/data/touhou_little_maid")
    val output = generatedContentDir.get().asFile
    inputs.dir(assets)
    inputs.dir(data)
    outputs.dir(output)
    doFirst { output.deleteRecursively() }
    val manifest = convertedModelsDir.get().file("tlm_models.json").asFile
    dependsOn(convertModels)
    inputs.file(manifest)
    args(assets.absolutePath, data.absolutePath, output.absolutePath, manifest.absolutePath, project.version.toString())
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.processResources {
    dependsOn(convertModels, generateContent)

    // 释放给 CraftEngine 的内容包（物品/方块/家具定义 + 贴图 + 模型）
    from(generatedContentDir.map { it.dir("content") }) { into("content") }

    // 释放给 BetterModel 的骨骼模型（女仆 + 家具）
    from(convertedModelsDir) { into("models"); exclude("tlm_models.json", "gecko/**") }
    from(generatedContentDir.map { it.dir("models") }) { into("models") }

    // 运行时数据：由 A 从 <本插件数据目录>/runtime/ 读取（路径常量见 api 的 TLMContentContract）
    from(convertedModelsDir) { include("tlm_models.json"); into("runtime") }
    from(convertedModelsDir) { include("gecko/**"); into("runtime") }
    from(generatedContentDir) {
        include("altar_recipes.json", "board_models.json", "chair_models.json", "joy_models.json")
        into("runtime")
    }
    // runtime/altar/*.nbt 直接来自 src/main/resources/runtime/altar/，无需额外配置

    // 许可与署名必须随包分发（GPL-2.0 / CC BY-NC-SA 4.0 的义务）
    from(rootProject.file("LICENSE")) { into("META-INF") }
    from(rootProject.file("LICENSE-ASSETS")) { into("META-INF") }
    from(rootProject.file("THIRD-PARTY.md")) { into("META-INF") }
}

/*
 * 资产回归锁：B 是「资产承运方」，产物数量漂移必须显式失败，
 * 而不是静默产出一个缺模型的包。
 */
val verifyAssets = tasks.register("verifyAssets") {
    group = "verification"
    description = "校验转换产物数量（bbmodel / gecko / CE 内容包 / 运行时清单）"
    dependsOn(convertModels, generateContent)
    doLast {
        fun count(dir: File, suffix: String): Int =
            if (!dir.isDirectory) 0
            else dir.walkTopDown().count { it.isFile && it.name.endsWith(suffix) }

        val models = convertedModelsDir.get().asFile
        val content = generatedContentDir.get().asFile
        val bbmodels = count(models, ".bbmodel") + count(File(content, "models"), ".bbmodel")
        val gecko = count(File(models, "gecko"), ".json")
        val contentFiles = if (content.isDirectory) content.walkTopDown().count { it.isFile } else 0
        val manifests = listOf("altar_recipes.json", "board_models.json", "chair_models.json", "joy_models.json")
            .count { File(content, it).isFile }

        logger.lifecycle("[资产] bbmodel=$bbmodels gecko=$gecko CE 内容包=$contentFiles 运行时清单=$manifests")
        check(bbmodels == 315) { "bbmodel 数量异常：期望 315，实际 $bbmodels" }
        check(gecko == 28) { "gecko 动画数量异常：期望 28，实际 $gecko" }
        check(manifests == 4) { "运行时清单缺失：期望 4，实际 $manifests" }
    }
}

tasks.named("check") { dependsOn(verifyAssets) }
