plugins {
    // 没有这个解析器时，机器上缺 JDK 25 会直接构建失败（不会自动下载）
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "XiaoMengMaidLib"

// 共享接口契约（MIT）：A/B 都只依赖它；类只打进 plugin 的 jar，单点携带
include("tlm-folia-api")
project(":tlm-folia-api").projectDir = file("api")

// 免费开源包本体（GPL-2.0 代码 + CC BY-NC-SA 4.0 素材）
include("plugin")

// 构建期工具：原模组资源 → CraftEngine/BetterModel 产物（不打进插件）
include("converter")

// converter 依赖的 Molang 解析器（与 A 运行时同一套实现）
include("molang")
