package com.proxy.interceptor.proxy;

import com.proxy.interceptor.config.SslConfig;
import com.proxy.interceptor.service.BlockedQueryService;
import com.proxy.interceptor.service.MetricsService;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class ProxyServer {

    @Value("${proxy.listen-port}")
    private int listenPort;

    @Value("${proxy.target-host}")
    private String targetHost;

    @Value("${proxy.target-port}")
    private int targetPort;

    @Value("${proxy.ssl.enabled}")
    private boolean sslEnabled;

    private final SqlClassifier sqlClassifier;
    private final WireProtocolHandler protocolHandler;
    private final BlockedQueryService blockedQueryService;
    private final MetricsService metricsService;
    private final EventLoopGroupFactory eventLoopGroupFactory;
    private final SslConfig sslConfig;
    private final SslContext proxySslContext;
    private final SslContext postgresClientSslContext;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private final ConcurrentHashMap<String, ConnectionState> connections = new ConcurrentHashMap<>();
    private final AtomicInteger connectionCounter = new AtomicInteger(0);

    public ProxyServer(SqlClassifier sqlClassifier,
                       WireProtocolHandler protocolHandler,
                       BlockedQueryService blockedQueryService,
                       MetricsService metricsService,
                       EventLoopGroupFactory eventLoopGroupFactory,
                       SslConfig sslConfig,
                       @Qualifier("proxySslContext") SslContext proxySslContext,
                       @Qualifier("postgresClientSslContext") SslContext postgresClientSslContext) {
        this.sqlClassifier = sqlClassifier;
        this.protocolHandler = protocolHandler;
        this.blockedQueryService = blockedQueryService;
        this.metricsService = metricsService;
        this.eventLoopGroupFactory = eventLoopGroupFactory;
        this.sslConfig = sslConfig;
        this.proxySslContext = proxySslContext;
        this.postgresClientSslContext = postgresClientSslContext;
    }

    @PostConstruct
    public void start() throws InterruptedException {

        bossGroup = eventLoopGroupFactory.createBossGroup();
        workerGroup = eventLoopGroupFactory.createWorkerGroup();

        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(eventLoopGroupFactory.getServerChannelClass())
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        String connId = "conn-" + connectionCounter.incrementAndGet();
                        ConnectionState state = new ConnectionState(connId);
                        connections.put(connId, state);
                        metricsService.trackConnection();

                        ch.pipeline().addLast(
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
                                        ch,
                                        connections
                                )
                        );
                    }
                });

        serverChannel = b.bind(listenPort).sync().channel();
        log.info("PostgreSQL Proxy listening on {}", listenPort);
    }

    @PreDestroy
    public void stop() {
        if (serverChannel != null) serverChannel.close();
        if (workerGroup != null) workerGroup.shutdownGracefully();
        if (bossGroup != null) bossGroup.shutdownGracefully();
    }
}
