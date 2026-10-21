/*
 * 共享接口契约（MIT）。
 *
 * 只放 A/B 双方都要引用的类型：棋类服务接口、棋盘坐标记录、数据契约常量。
 * 本模块的 class 只打进 plugin 的 jar（单点携带），A 以 compileOnly 依赖 + 运行时 join-classpath 获取，
 * 严禁在 A 的 jar 里重复打包。
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
