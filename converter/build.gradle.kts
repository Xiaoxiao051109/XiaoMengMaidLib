/*
 * 本地模型转换工具：Bedrock 模型包 → BetterModel .bbmodel。
 * 只在开发机/构建期运行，不打进插件 jar；插件只携带它的产物。
 *
 * 单独运行：
 *   .\gradlew.bat :converter:run --args="<模型包目录> <输出目录>"
 */
plugins {
    java
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    // 插件运行时同一套 Molang 解析器：校验 Gecko 动画表达式
    implementation(project(":molang"))
    // BetterModel 运行时使用的 Molang 编译器（同版本），转换期用它校验每个数据点，
    // 编译不过的直接降级为常量，避免到服务器上才发现
    implementation(files("../libs/molang-compiler-3.1.1.19.jar"))
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-tree:9.9.1")
    implementation("org.ow2.asm:asm-commons:9.9.1")
}

application {
    mainClass = "io.github.tlm.converter.convert.ModelPackConverter"
    // Windows 控制台默认 GBK，统一 UTF-8 输出避免中文日志乱码
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
