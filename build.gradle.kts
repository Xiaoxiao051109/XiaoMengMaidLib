// 版本与分组统一从根 gradle.properties 继承；各子模块自行声明 plugins / dependencies
allprojects {
    group = property("group") as String
    version = property("version") as String
}

// 编码统一下沉，杜绝子模块漏配（Windows 默认 GBK 会把中文注释编译报错）
subprojects {
    plugins.withId("java") {
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
        }
    }
}
