package io.github.tlm.folia.api;

/**
 * A（付费核心）与 B（开源义务包）之间的数据契约。
 *
 * <p>B 在启动时把 jar 内的 {@code runtime/} 释放到自己的数据目录，A 只按本类中的常量读取；
 * 协议版本不匹配时 A 应拒绝启用并提示升级 B。
 */
public final class TLMContentContract {

    /** B 的插件名（paper-plugin.yml 的 name）。 */
    public static final String PLUGIN_NAME = "XiaoMengMaidLib";

    /** 内容协议版本：A 与 B 必须一致，破坏性变更时递增。 */
    public static final String PROTOCOL_VERSION = "1";

    /** 协议版本文件名，位于 B 的 runtime 目录下。 */
    public static final String PROTOCOL_FILE = "protocol.txt";

    /** B 数据目录下的运行时数据目录名。 */
    public static final String RUNTIME_DIR = "runtime";

    /** 模型清单（隐藏骨骼、Gecko 标记、显示名、女仆模型 id）。 */
    public static final String MODEL_MANIFEST = "tlm_models.json";

    /** 椅子模型清单。 */
    public static final String CHAIR_MANIFEST = "chair_models.json";

    /** 娱乐家具清单。 */
    public static final String JOY_MANIFEST = "joy_models.json";

    /** 棋盘几何清单。 */
    public static final String BOARD_MANIFEST = "board_models.json";

    /** 祭坛配方清单。 */
    public static final String ALTAR_RECIPES = "altar_recipes.json";

    /** 祭坛多方块结构目录（内含 altar_east/north/south/west.nbt）。 */
    public static final String ALTAR_DIR = "altar";

    /** Gecko 动画目录（内含 &lt;modelKey&gt;.json）。 */
    public static final String GECKO_DIR = "gecko";

    private TLMContentContract() {
    }
}
