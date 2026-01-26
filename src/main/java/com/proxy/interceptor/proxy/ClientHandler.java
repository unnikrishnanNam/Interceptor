package com.proxy.interceptor.proxy;

import com.proxy.interceptor.service.BlockedQueryService;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClientHandler extends ChannelInboundHandlerAdapter {

    private final String connId;
    private final ConnectionState state;
    private final String targetHost;
    private final int targetPort;
    private final SqlClassifier sqlClassifier;
    private final WireProtocolHandler protocolHandler;
    private final BlockedQueryService blockedQueryService;

    private Channel clientChannel;

    public ClientHandler(String connId,
                         ConnectionState state,
                         String targetHost,
                         int targetPort,
                         SqlClassifier sqlClassifier,
                         WireProtocolHandler protocolHandler,
                         BlockedQueryService blockedQueryService) {
        this.connId = connId;
        this.state = state;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.sqlClassifier = sqlClassifier;
        this.protocolHandler = protocolHandler;
        this.blockedQueryService = blockedQueryService;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        clientChannel = ctx.channel();

        // Connect to the PostgreSQL db engine
        Bootstrap b= new Bootstrap();
        b.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ServerHandler(connId, clientChannel));
                    }
                });
        b.connect(targetHost, targetPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                state.serverChannel = future.channel();
                log.debug("{}: Connected to PostgreSQL db engine", connId);
            } else {
                log.error("{}: Failed to connect to PostgreSQL", connId);
                ctx.close();
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;

        try {
            // Check for Simple Query
            var simpleQuery = protocolHandler.parseSimpleQuery(buf.duplicate());
            if (simpleQuery.isPresent()) {
                String sql = simpleQuery.get();

                if (sqlClassifier.shouldBlock(sql)) {
                    log.info("{}: BLOCKED Simple Query: {}",
                            connId, sql.substring(0, Math.min(100, sql.length())));

                    blockedQueryService.addBlockedQuery(
                            connId,
                            "SIMPLE",
                            sql,
                            buf.retainedDuplicate(),
                            this::forwardToServer,
                            error -> sendErrorToClient(ctx, error)
                    );

                    return; // Don't forward yet
                }
            }

            // Check for Extended Protocol (Parse message)
            var extendedQuery = protocolHandler.parseExtendedQuery(buf.duplicate());
            if (extendedQuery.isPresent()) {
                String sql = extendedQuery.get();

                if (sqlClassifier.shouldBlock(sql)) {
                    state.inExtendedBatch = true;
                    state.batchQuery = new StringBuilder(sql);
                }
            }

            // Handle Sync message (end of extended batch)
            if (protocolHandler.isSyncMessage(buf.duplicate()) && state.inExtendedBatch) {
                String sql = state.batchQuery.toString();

                log.info("{}: BLOCKED Extended Query: {}",
                        connId, sql.substring(0, Math.min(100, sql.length())));

                state.inExtendedBatch = false;
                state.batchQuery = new StringBuilder();

                blockedQueryService.addBlockedQuery(
                        connId,
                        "EXTENDED",
                        sql,
                        buf.retainedDuplicate(),
                        this::forwardToServer,
                        error -> sendErrorToClient(ctx, error)
                );

                return;
            }

            // Forward to PostgreSQL db engine
            forwardToServer(buf.retain());
        } finally {
            buf.release();
        }
    }

    private void forwardToServer(ByteBuf buf) {
        if (state.serverChannel != null && state.serverChannel.isActive()) {
            state.serverChannel.writeAndFlush(buf);
        } else {
            buf.release(); // prevent leak if server not available
        }
    }

    private void sendErrorToClient(ChannelHandlerContext ctx, String message) {
        ByteBuf error = protocolHandler.createErrorResponse(message);
        ByteBuf ready = protocolHandler.createReadyForQuery();
        ctx.write(error);
        ctx.writeAndFlush(ready);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("{}: Client disconnected", connId);

        blockedQueryService.cleanupConnection(connId);

        if (state.serverChannel != null) {
            state.serverChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("{}: Client error: {}", connId, cause.getMessage());
        ctx.close();
    }
}
