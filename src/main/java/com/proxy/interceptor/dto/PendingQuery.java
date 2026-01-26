package com.proxy.interceptor.dto;

import io.netty.buffer.ByteBuf;
import java.util.Set;
import java.util.function.Consumer;

public record PendingQuery(
        Long id,
        String connId,
        ByteBuf originalMessage,
        Consumer<ByteBuf> forwardCallback,
        Consumer<String> rejectCallback,
        Set<String> approvals,
        Set<String> rejections
) {}
