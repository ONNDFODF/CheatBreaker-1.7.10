package com.cheatbreaker.client.network;

import com.cheatbreaker.client.CheatBreaker;
import com.cheatbreaker.client.discord.DiscordRpcManager;
import com.cheatbreaker.client.event.S01PacketJoinGameEvent;
import com.cheatbreaker.client.event.SimpleEvent1;
import com.cheatbreaker.client.friend.FriendOnlineStatus;
import com.cheatbreaker.client.manager.AbstractManager;
import com.cheatbreaker.client.util.SneakyThrow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * player assets 连接控制器。
 *
 * <p>这里不再向 CheatBreaker 的 player assets 服务器发起任何连接：客户端始终以离线身份运行，
 * 装扮、表情、好友列表全部来自本地。{@link #connect()} 只安装一个
 * {@link OfflineWebsocketNetHandler} 占位实现，保证各处对
 * {@code getPlayerassetsCheatbreakerNet()} 的调用不会出现空指针。
 */
public class WebsocketController extends AbstractManager {
    public DiscordRpcManager discordRpcManager = CheatBreaker.getInstance().getDiscordRpcManager();
    public boolean flag = false;
    public boolean flag2 = true;
    public FriendOnlineStatus friendOnlineStatus = FriendOnlineStatus.ONLINE;
    public Logger logger = LogManager.getLogger("WS Controller");

    public void onSimpleEvent1(SimpleEvent1 simpleEvent1) {
        this.cheatBreaker.playerassetsCheatbreakerNet.close();
    }

    public void setFlag2(boolean flag) {
        this.flag2 = flag;
    }

    public DiscordRpcManager getDiscordRpcManager() {
        return this.discordRpcManager;
    }

    public void onS01PacketJoinGameEvent(S01PacketJoinGameEvent s01PacketJoinGameEvent) {
        ServerData currentServerData = Minecraft.getMinecraft().getCurrentServerData();
        if (currentServerData != null) {
            this.onServerChange(currentServerData.serverIP, currentServerData.text, currentServerData.intValue);
        }
    }

    /** 服务器切换时只更新本地的 Discord 状态，不再同步到 player assets 服务器。 */
    public void onServerChange(String str, String str2, int n) {
        try {
            this.cheatBreaker.getDiscordRpcManager().serverAddress = str;
            if (this.cheatBreaker.getKeybindSettings().showActiveAccount.getBooleanValue()) {
                this.discordRpcManager.updatePresence(this.mc.getSession().getUsername());
            } else {
                this.discordRpcManager.updatePresence(null);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        boolean flag = str.equals(str2 + ":" + n);
        this.cheatBreaker.getDiscordRpcManager().serverAddress = flag ? "server" : (str.isEmpty() ? "In-Menus" : str);
    }

    public Logger getLogger() {
        return this.logger;
    }

    @Override
    public void initialize() {
        try {
            this.connect();
        } catch (Throwable e) {
            throw SneakyThrow.sneakyThrow(e);
        }
    }

    /** 离线模式下没有需要维持的心跳。 */
    public void startHeartbeat() {
        this.logger.debug("Offline mode: heartbeat disabled");
    }

    public FriendOnlineStatus getFriendOnlineStatus() {
        return this.friendOnlineStatus;
    }

    public boolean isFlag2() {
        return this.flag2;
    }

    public WebsocketController() {
        this.cheatBreaker.getEventBus().checkClass2(S01PacketJoinGameEvent.class, this::onS01PacketJoinGameEvent);
        this.cheatBreaker.getEventBus().checkClass2(SimpleEvent1.class, this::onSimpleEvent1);
    }

    public void setFlag(boolean flag) {
        this.flag = flag;
    }

    public boolean isFlag() {
        return this.flag;
    }

    /** 安装离线占位连接：不解析地址、不打开套接字、不上报任何客户端信息。 */
    public void connect() throws java.net.URISyntaxException {
        if (this.cheatBreaker.playerassetsCheatbreakerNet != null) {
            this.cheatBreaker.playerassetsCheatbreakerNet.close();
        }

        this.logger.debug("Offline mode: player assets server connection disabled");
        this.cheatBreaker.playerassetsCheatbreakerNet = new OfflineWebsocketNetHandler();
        this.cheatBreaker.setWebsocketConnected(false);
    }

    public void setFriendOnlineStatus(FriendOnlineStatus friendOnlineStatus) {
        this.friendOnlineStatus = friendOnlineStatus;
    }
}
