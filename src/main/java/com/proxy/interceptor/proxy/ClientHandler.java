package com.proxy.interceptor.proxy;

import com.proxy.interceptor.service.BlockedQueryService;
import com.proxy.interceptor.service.MetricsService;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ClientHandler extends ChannelInboundHandlerAdapter {

    private final String connId;
    private final ConnectionState state;
    private final String targetHost;
    private final int targetPort;
    private final SqlClassifier sqlClassifier;
    private final WireProtocolHandler protocolHandler;
    private final BlockedQueryService blockedQueryService;
    private final MetricsService metricsService;
    private final EventLoopGroupFactory eventLoopGroupFactory;

    private Channel clientChannel;

    public ClientHandler(String connId,
                         ConnectionState state,
                         String targetHost,
                         int targetPort,
                         SqlClassifier sqlClassifier,
                         WireProtocolHandler protocolHandler,
                         BlockedQueryService blockedQueryService,
                         MetricsService metricsService,
                         EventLoopGroupFactory eventLoopGroupFactory) {
        this.connId = connId;
        this.state = state;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.sqlClassifier = sqlClassifier;
        this.protocolHandler = protocolHandler;
        this.blockedQueryService = blockedQueryService;
        this.metricsService = metricsService;
        this.eventLoopGroupFactory = eventLoopGroupFactory;
    }

    /*
    * Connection lifecycle
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        clientChannel = ctx.channel();

        // Connect to the PostgreSQL db engine
        Bootstrap b= new Bootstrap();
        b.group(ctx.channel().eventLoop())
                .channel(eventLoopGroupFactory.getServerChannelClass())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                                new ServerHandler(connId, clientChannel, metricsService)
                        );
                    }
                });

        b.connect(targetHost, targetPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                state.serverChannel = future.channel();
                log.debug("{}: Connected to PostgreSQL db engine", connId);
            } else {
                log.error("{}: Failed to connect to PostgreSQL", connId);
                metricsService.trackError();
                sendErrorToClient(ctx, "Failed to connect to db engine");
                ctx.close();
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;

        try {
            // Wait for server connection to be established
            if (state.serverChannel == null || !state.serverChannel.isActive()) {
                log.debug("{}: Server not connected yet, buffering message", connId);
                scheduleForward(ctx, buf.retain());
                return;
            }
            processClientMessage(ctx, buf);
        } finally {
            buf.release();
        }
    }

    /*
    * Message Processing
     */

    private void processClientMessage(ChannelHandlerContext ctx, ByteBuf buf) {
        if (buf.readableBytes() < 1) {
            forwardToServer(buf.retain());
            return;
        }

        byte messageType = buf.getByte(buf.readerIndex());

        switch (messageType) {
            case 'Q' -> handleSimpleQuery(ctx, buf);
            case 'P' -> handleParseMessage(buf);
            case 'S' -> handleSyncMessage(ctx, buf);
            case 'B', 'D', 'E' -> handleExtendedProtocolMessage(buf);
            default -> forwardToServer(buf.retain());
        }
    }

    /*
    * Simple Query
     */
    private void handleSimpleQuery(ChannelHandlerContext ctx, ByteBuf buf) {
        var simpleQuery = protocolHandler.parseSimpleQuery(buf.duplicate());
        if (simpleQuery.isPresent()) {
            String sql = simpleQuery.get();
            metricsService.trackQuery("SIMPLE");

            if (sqlClassifier.shouldBlock(sql)) {
                log.info("{}: BLOCKED Simple Query: {}", connId, truncate(sql));
                metricsService.trackBlocked();

                blockedQueryService.addBlockedQuery(
                        connId,
                        "SIMPLE",
                        sql,
                        buf.retainedDuplicate(),
                        this::forwardToServer,
                        error -> sendErrorToClient(ctx, error)
                );
                return;
            }
        }
        forwardToServer(buf.retain());
    }

    /*
    * Extended Query
     */
    private void handleParseMessage(ByteBuf buf) {
        var extendedQuery = protocolHandler.parseExtendedQuery(buf.duplicate());
        if (extendedQuery.isPresent()) {
            String sql = extendedQuery.get();

            if (sqlClassifier.shouldBlock(sql)) {
                log.debug("{}: Starting blocked extended batch", connId);
                state.inExtendedBatch = true;
                state.batchQuery = new StringBuilder(sql);
                state.batchBuffers.add(buf.retainedDuplicate());
                return;
            }
        }
        forwardToServer(buf.retain());
    }

    private void handleExtendedProtocolMessage(ByteBuf buf) {
        if (state.inExtendedBatch) {
            state.batchBuffers.add(buf.retainedDuplicate());
        } else {
            forwardToServer(buf.retain());
        }
    }

    private void handleSyncMessage(ChannelHandlerContext ctx, ByteBuf buf) {
        if (!state.inExtendedBatch) {
            forwardToServer(buf.retain());
            return;
        }

        state.batchBuffers.add(buf.retainedDuplicate());
        String sql = state.batchQuery.toString();

        log.info("{}: BLOCKED Extended Query: {}", connId, truncate(sql));
        metricsService.trackQuery("EXTENDED");
        metricsService.trackBlocked();

        ByteBuf combinedBuf = ctx.alloc().compositeBuffer()
                .addComponents(true, state.batchBuffers.toArray(new ByteBuf[0]));

        state.inExtendedBatch = false;
        state.batchQuery = new StringBuilder();
        state.batchBuffers.clear();

        blockedQueryService.addBlockedQuery(
                connId,
                "EXTENDED",
                sql,
                combinedBuf,
                this::forwardToServer,
                error -> sendErrorToClient(ctx, error)
        );
    }

    /*
    * Forwarding helpers
     */
    private void forwardToServer(ByteBuf buf) {
        if (state.serverChannel != null && state.serverChannel.isActive()) {
            state.serverChannel.writeAndFlush(buf);
        } else {
            buf.release(); // prevent leak if server not available
            log.warn("{}: Cannot forward - server channel inactive", connId);
        }
    }

    private void scheduleForward(ChannelHandlerContext ctx, ByteBuf buf) {
        ctx.channel().eventLoop().schedule(() -> {
            if (state.serverChannel != null && state.serverChannel.isActive()) {
                try {
                    processClientMessage(ctx, buf);
                } finally {
                    buf.release();
                }
            } else if (ctx.channel().isActive()) {
                scheduleForward(ctx, buf);
            } else {
                buf.release();
            }
        }, 10, TimeUnit.MILLISECONDS);
    }

    private void sendErrorToClient(ChannelHandlerContext ctx, String message) {
        if (!ctx.channel().isActive()) return;

        ByteBuf error = protocolHandler.createErrorResponse(message);
        ByteBuf ready = protocolHandler.createReadyForQuery();
        ctx.write(error);
        ctx.writeAndFlush(ready);
    }

    /*
    * Cleanup
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("{}: Client disconnected", connId);
        metricsService.trackDisconnection();
        blockedQueryService.cleanupConnection(connId);
        state.resetBatch();

        if (state.serverChannel != null) {
            state.serverChannel.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("{}: Client error: {}", connId, cause.getMessage());
        metricsService.trackError();
        state.resetBatch();
        ctx.close();
    }

    /*
    * Utilities
     */
    private String truncate(String s) {
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}
