package io.github.tlm.folia.open;

import io.github.tlm.folia.api.TLMContentContract;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.stream.Stream;

/**
 * 把打进 jar 的资产释放到三个目标：
 *
 * <ul>
 *   <li>{@code content/} → {@code CraftEngine/resources/touhou_little_maid/}；</li>
 *   <li>{@code models/}  → {@code BetterModel/models/touhou_little_maid/}；</li>
 *   <li>{@code runtime/} → 本插件数据目录下的 {@code runtime/}（A 通过数据契约读取）。</li>
 * </ul>
 *
 * <p>靠版本戳文件判断是否需要重新释放：插件版本变化才覆盖，这样服主对释放出来的
 * YAML 做的调整在同一版本内不会被冲掉。{@code protocol.txt} 每次启动都会刷新。
 */
public final class AssetsInstaller {

    private static final String STAMP_FILE = ".open-version";

    private AssetsInstaller() {
    }

    public static void install(Plugin plugin) {
        Path pluginsDir = plugin.getDataFolder().toPath().getParent();
        String version = plugin.getPluginMeta().getVersion();

        copyTree(plugin, "content",
                pluginsDir.resolve("CraftEngine").resolve("resources").resolve("touhou_little_maid"), version, false, false);
        // 模型是生成资产，同版本也要覆盖：转换器修正 display 骨骼/旋转后不能靠版本号跳过。
        copyTree(plugin, "models",
                pluginsDir.resolve("BetterModel").resolve("models").resolve("touhou_little_maid"), version, false, true);

        Path runtime = plugin.getDataFolder().toPath().resolve(TLMContentContract.RUNTIME_DIR);
        // runtime 里会追加新的核心动画文件；同版本时只补缺失文件，不覆盖服主已有修改。
        copyTree(plugin, "runtime", runtime, version, true, false);
        writeProtocol(plugin, runtime);
    }

    private static void copyTree(Plugin plugin, String jarPrefix, Path target, String version,
                                 boolean copyMissingWhenCurrent, boolean overwriteWhenCurrent) {
        Path stamp = target.resolve(STAMP_FILE);
        boolean current = false;
        if (Files.isRegularFile(stamp)) {
            try {
                if (version.equals(Files.readString(stamp).trim())) {
                    current = true;
                    if (!copyMissingWhenCurrent && !overwriteWhenCurrent) {
                        plugin.getLogger().info("[资产] " + jarPrefix + " 已是当前版本，跳过释放。");
                        return;
                    }
                }
            } catch (IOException ignored) {
                // 戳文件读不出来就当作需要重新释放
            }
        }

        URI jarUri = locateJar(plugin);
        if (jarUri == null) {
            plugin.getLogger().warning("[资产] 无法定位插件 jar，跳过 " + jarPrefix + " 释放。");
            return;
        }

        int count = 0;
        try (FileSystem jar = FileSystems.newFileSystem(jarUri, Collections.emptyMap())) {
            Path source = jar.getPath("/" + jarPrefix);
            if (!Files.isDirectory(source)) {
                plugin.getLogger().warning("[资产] jar 内无 " + jarPrefix + "/，跳过。");
                return;
            }
            Files.createDirectories(target);
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path entry : (Iterable<Path>) walk::iterator) {
                    Path relative = source.relativize(entry);
                    Path destination = target.resolve(relative.toString());
                    if (Files.isDirectory(entry)) {
                        Files.createDirectories(destination);
                    } else {
                        if (current && copyMissingWhenCurrent && !overwriteWhenCurrent && Files.exists(destination)) {
                            continue;
                        }
                        Files.createDirectories(destination.getParent());
                        Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
                        count++;
                    }
                }
            }
            if (!current) {
                Files.writeString(stamp, version);
            }
            plugin.getLogger().info("[资产] 已释放 " + count + " 个文件 -> " + target);
        } catch (IOException e) {
            plugin.getLogger().warning("[资产] 释放 " + jarPrefix + " 失败: " + e.getMessage()
                    + "（可手动复制到 " + target + "）");
        }
    }

    private static void writeProtocol(Plugin plugin, Path runtime) {
        try {
            Files.createDirectories(runtime);
            Files.writeString(runtime.resolve(TLMContentContract.PROTOCOL_FILE),
                    TLMContentContract.PROTOCOL_VERSION);
        } catch (IOException e) {
            plugin.getLogger().warning("[资产] 写入协议版本失败: " + e.getMessage());
        }
    }

    /** 定位本插件自身的 jar，用于以 zip 文件系统遍历内部目录。 */
    private static URI locateJar(Plugin plugin) {
        try {
            var location = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            return URI.create("jar:" + location.toURI());
        } catch (Exception e) {
            return null;
        }
    }
}
