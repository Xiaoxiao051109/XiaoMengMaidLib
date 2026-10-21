package io.github.tlm.folia.open;

import io.github.tlm.folia.api.BoardGame;
import io.github.tlm.folia.open.board.MobileChessGame;
import io.github.tlm.folia.open.board.XqwlightGame;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 免费开源义务包（B）。
 *
 * <p>职责只有三件：
 * <ol>
 *   <li>把 jar 内的转换产物释放到 CraftEngine / BetterModel / 自身数据目录；</li>
 *   <li>写入内容协议版本（{@code runtime/protocol.txt}）；</li>
 *   <li>通过 ServicesManager 注册两个棋盘对局服务，供 A 消费。</li>
 * </ol>
 *
 * <p>本插件不包含任何 A 的业务逻辑，也不依赖 A；缺失 A 时仍可独立启动并释放资产。
 * 代码许可见 LICENSE（GPL-2.0），素材许可见 LICENSE-ASSETS（CC BY-NC-SA 4.0）。
 */
public final class OpenPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        AssetsInstaller.install(this);

        XqwlightGame xqwlight = new XqwlightGame();
        MobileChessGame mobile = new MobileChessGame();
        var services = getServer().getServicesManager();
        services.register(BoardGame.class, xqwlight, this, ServicePriority.Normal);
        services.register(BoardGame.class, mobile, this, ServicePriority.Normal);

        getLogger().info("已启用开源内容包（协议 " + io.github.tlm.folia.api.TLMContentContract.PROTOCOL_VERSION
                + "），已注册棋类服务：" + xqwlight.id() + " / " + mobile.id());
    }
}
