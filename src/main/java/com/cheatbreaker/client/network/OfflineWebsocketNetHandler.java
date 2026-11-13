package com.cheatbreaker.client.network;

import java.net.URI;
import java.util.Collections;

/**
 * 离线占位连接。
 *
 * <p>客户端里到处都在用 {@code getPlayerassetsCheatbreakerNet()}：好友、表情轮盘、
 * 装扮设置同步、篡改上报都会经过它。为了让这些本地调用点保持可用（不出现空指针），
 * 同时彻底断开与 player assets 服务器的连接，这里给出一个不打开任何套接字的实现：
 * 连接、关闭、发包、心跳全部变成空操作，本地状态仍由各自的逻辑维护。
 */
public class OfflineWebsocketNetHandler extends WebsocketNetHandler {
    /** 仅用于满足父类构造，不会被解析或连接。 */
    private static final URI PLACEHOLDER_URI = URI.create("ws://127.0.0.1:0");

    public OfflineWebsocketNetHandler() {
        super(PLACEHOLDER_URI, Collections.<String, String>emptyMap());
    }

    @Override
    public void connect() {
        // 离线模式：不建立连接
    }

    @Override
    public void close() {
        // 没有连接需要关闭；也不清空本地已解锁的表情
    }

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public void sendPacket(AbstractPacket packet) {
        // 离线模式：不向服务器发送任何数据
    }
}
