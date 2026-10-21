# XiaoMengMaidLib

「车万女仆」（Touhou Little Maid）Paper/Folia 移植版的**免费开源义务包**。

本包承担全部「必须开源」的部分：

- 由原模组转换而来的**全部素材**（CraftEngine 内容包、BetterModel 模型、Gecko 动画、音效、运行时清单、祭坛结构）；
- **GPL-2.0 的象棋引擎**（XiangQi Wizard Light / Mobile Chess）与两个棋盘对局实现；
- 资产释放器与 `BoardGame` 服务注册。

付费闭源的核心包 `XiaoMengMaid` 通过 Bukkit ServicesManager 与
`<本插件数据目录>/runtime/` 数据契约消费本包，两者**缺一不可**。

## 许可（分组件）

| 组件 | 许可 |
|---|---|
| 代码（`api/`、`plugin/`、`converter/`、`molang/`） | GPL-2.0（见 `LICENSE`） |
| 由原模组转换的素材 | CC BY-NC-SA 4.0（见 `LICENSE-ASSETS`） |

素材为非商业授权：**不得用于任何付费或变现产品/服务**。代码部分可自由使用、修改、
再分发（须保留源码与声明）。

## 构建

需要 JDK 25，并在同级目录放置原模组源码树 `TouhouLittleMaid-26.1-gizmo_fix_26.2`
（路径可用 `-PtlmSource` / `-PtlmSourceRoot` 覆盖）：

```bash
./gradlew :plugin:jar
# 产物：plugin/build/libs/XiaoMengMaidLib-<version>.jar
```

构建期会运行 `converter`，从原模组资源生成 `content/`、`models/`、`runtime/` 并打进 jar。

## 预构建包（`dist/`）

仓库的 `dist/` 目录附带与源码同版本的成品 jar，内含**全部代码与转换后的资源**
（CraftEngine 内容包、BetterModel 模型、Gecko 动画、音效、运行时清单、祭坛结构、象棋引擎）：

```
dist/XiaoMengMaidLib-<version>.jar
```

不想自己构建时，直接取这个 jar 放进服务器 `plugins/` 即可（需同时安装核心包
`XiaoMengMaid` 以及 CraftEngine、BetterModel）。

## 运行时

1. 服务器安装 CraftEngine 与 BetterModel；
2. 把本 jar 放入 `plugins/`（与核心包一起）；
3. 启动后资产会释放到：
   - `plugins/CraftEngine/resources/touhou_little_maid/`
   - `plugins/BetterModel/models/touhou_little_maid/`
   - `plugins/XiaoMengMaidLib/runtime/`（含 `protocol.txt`）

## 数据契约

见 `api/src/main/java/io/github/tlm/folia/api/TLMContentContract.java`。
协议版本必须与核心包一致，不一致时核心包会拒绝启用。

## 致谢

见 `THIRD-PARTY.md`。
