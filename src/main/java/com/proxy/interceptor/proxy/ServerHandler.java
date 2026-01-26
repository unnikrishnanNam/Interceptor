package com.proxy.interceptor.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerHandler extends ChannelInboundHandlerAdapter {

    private final String connId;
    private final Channel clientChannel;

    public ServerHandler(String connId, Channel clientChannel) {
        this.connId = connId;
        this.clientChannel = clientChannel;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // Forward server response to client
        if (clientChannel.isActive()) {
            clientChannel.writeAndFlush(msg);
        } else {
            // If client is dead, release message to avoid leaks
            ReferenceCountUtil.release(msg);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("{}: Server connection closed", connId);
        clientChannel.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("{}: Server error: {}", connId, cause.getMessage());
        ctx.close();
    }
}
