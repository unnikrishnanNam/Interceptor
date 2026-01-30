package com.proxy.interceptor.proxy;

import com.proxy.interceptor.service.BlockedQueryService;
import com.proxy.interceptor.service.MetricsService;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class PostgresSSLNegotiationHandler extends ChannelInboundHandlerAdapter {

    private final String connId;
    private final ConnectionState state;
    private final Channel clientChannel;
    private final String targetHost;
    private final int targetPort;
    private final boolean sslEnabled;
    private final SslContext proxySslContext;
    private final SslContext postgresClientSslContext;
    private final SqlClassifier sqlClassifier;
    private final EventLoopGroupFactory eventLoopGroupFactory;
    private final WireProtocolHandler protocolHandler;
    private final BlockedQueryService blockedQueryService;
    private final MetricsService metricsService;
    private ConcurrentHashMap<String, ConnectionState> connections;

    private boolean startupReceived = false;

    public PostgresSSLNegotiationHandler(
            String connId,
            ConnectionState state,
            Channel clientChannel,
            String targetHost,
            int targetPort,
            boolean sslEnabled,
            SslContext proxySslContext,
            SslContext postgresClientSslContext,
            SqlClassifier sqlClassifier,
            EventLoopGroupFactory eventLoopGroupFactory,
            WireProtocolHandler protocolHandler,
            BlockedQueryService blockedQueryService,
            MetricsService metricsService,
            ConcurrentHashMap<String, ConnectionState> connections
    ) {
        this.connId = connId;
        this.state = state;
        this.clientChannel = clientChannel;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.sslEnabled = sslEnabled;
        this.proxySslContext = proxySslContext;
        this.postgresClientSslContext = postgresClientSslContext;
        this.sqlClassifier = sqlClassifier;
        this.eventLoopGroupFactory = eventLoopGroupFactory;
        this.protocolHandler = protocolHandler;
        this.blockedQueryService = blockedQueryService;
        this.metricsService = metricsService;
        this.connections = connections;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Don't connect to db engine - wait for startup/SSL negotiation
        log.debug("{}: Channel active, waiting for startup message", connId);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ByteBuf buf = (ByteBuf) msg;

        try {
            if (!startupReceived && buf.readableBytes() >= 8) {
                // Check for SSLRequest (length=8, code=80877103)
                int length = buf.getInt(buf.readerIndex());
                int code = buf.getInt(buf.readerIndex() + 4);

                if (length == 8 && code == 80877103) {
                    // SSLRequest message
                    log.debug("{}: Received SSLRequest", connId);
                    handleSSLRequest(ctx);
                    return;
                }

                // StartupMessage
                startupReceived = true;
                log.debug("{}: Received StartupMessage, connecting to PostgreSQL", connId);

                ctx.pipeline().replace(
                        this,
                        "clientHandler",
                        new ClientHandler(
                                connId,
                                state,
                                targetHost,
                                targetPort,
                                sqlClassifier,
                                protocolHandler,
                                blockedQueryService,
                                metricsService,
                                eventLoopGroupFactory,
                                ctx.channel(),
                                connections
                        )
                );

                // Connect to db engine and forward startup message
                connectToPostgres(ctx, buf.retainedDuplicate());
                return;
            }

            // Forward other messages
            if (state.serverChannel != null && state.serverChannel.isActive()) {
                state.serverChannel.writeAndFlush(buf.retainedDuplicate());
            }

        } finally {
            buf.release();
        }
    }

    private void handleSSLRequest(ChannelHandlerContext ctx) {
        ByteBuf response = ctx.alloc().buffer(1);

        if (sslEnabled && proxySslContext != null && !state.sslNegotiated) {
            response.writeByte('S');
            ctx.writeAndFlush(response);

            SslHandler sslHandler = proxySslContext.newHandler(ctx.alloc());
            ctx.pipeline().addFirst("ssl", sslHandler);
            state.sslNegotiated = true;
        } else {
            response.writeByte('N');
            ctx.writeAndFlush(response);
        }
    }

    private void connectToPostgres(ChannelHandlerContext ctx, ByteBuf startupMessage) {
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(ctx.channel().eventLoop())
                .channel(eventLoopGroupFactory.getServerChannelClass())
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addLast(new ServerHandler(connId, clientChannel, metricsService));
                    }
                });

        bootstrap.connect(targetHost, targetPort).addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                state.serverChannel = future.channel();
                state.serverChannel.writeAndFlush(startupMessage);
            } else {
                startupMessage.release();
                ByteBuf error = protocolHandler.createErrorResponse("Failed to connect to server");
                ctx.writeAndFlush(error);
                ctx.close();
            }
        });
    }
}
