package com.proxy.interceptor.proxy;

import io.netty.channel.Channel;

public class ConnectionState {

    public final String connId;
    public volatile Channel serverChannel;
    public volatile boolean isExtendedBatch = false;
    public volatile StringBuilder batchQuery = new StringBuilder();

    public ConnectionState(String connId) {
        this.connId = connId;
    }
}
