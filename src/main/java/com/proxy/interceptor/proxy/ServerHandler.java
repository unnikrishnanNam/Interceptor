package com.proxy.interceptor.proxy;

import com.proxy.interceptor.service.MetricsService;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ServerHandler extends ChannelInboundHandlerAdapter {

    private final String connId;
    private final Channel clientChannel;
    private final MetricsService metricsService;

    public ServerHandler(String connId,
                         Channel clientChannel,
                         MetricsService metricsService) {
        this.connId = connId;
        this.clientChannel = clientChannel;
        this.metricsService = metricsService;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        // Forward server response to client
        if (clientChannel.isActive()) {
            clientChannel.writeAndFlush(msg);
        } else {
            // If client is dead, release message to avoid leaks
            ((ByteBuf) msg).release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("{}: Server connection closed", connId);
        if (clientChannel.isActive()) {
            clientChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("{}: Server error: {}", connId, cause.getMessage());
        metricsService.trackError();
        ctx.close();
    }
}
