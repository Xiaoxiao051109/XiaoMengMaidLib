/*
 * 共享的 Molang 解析与求值：转换器用它校验动画表达式，插件运行时用它逐 tick 求值。
 * 无第三方依赖，打进插件 jar。
 */
plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
