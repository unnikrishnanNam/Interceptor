package com.proxy.interceptor.proxy;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
@Slf4j
public class WireProtocolHandler {

    /*
    * Parse a Simple Query(Q) message and extract the SQL.
    * Format: 'Q' (1 byte) + Length (4 bytes) + Query String + null-terminator (1 byte)
     */
    public Optional<String> parseSimpleQuery(ByteBuf buf) {
        if (buf.readableBytes() < 5) {
            return Optional.empty();
        }

        buf.markReaderIndex();
        byte messageType = buf.readByte();

        if (messageType != 'Q') {
            buf.resetReaderIndex();
            return Optional.empty();
        }

        int length = buf.readInt(); // Includes itself but not the type byte
        int queryLength = length - 4 - 1; // Subtract length field and null terminator

        if (buf.readableBytes() < queryLength + 1) {
            buf.resetReaderIndex();
            return Optional.empty();
        }

        byte[] queryBytes = new byte[queryLength];
        buf.readBytes(queryBytes);
        buf.readByte(); // Skip null terminator

        String sql = new String(queryBytes, StandardCharsets.UTF_8);
        log.debug("Parsed Simple Query: {}", sql.substring(0, Math.min(100, sql.length())));

        return Optional.of(sql);
    }

    /*
    * Parse a Parse (P) message to extract query preview for Extended Protocol.
    * Format: 'P' (1 byte) + Length (4 bytes) + Statement Name (C-string) + Query (C-string) + ...
     */

    public Optional<String> parseExtendedQuery(ByteBuf buf) {
        if (buf.readableBytes() < 5) {
            return Optional.empty();
        }

        buf.markReaderIndex();
        byte messageType = buf.readByte();

        if (messageType != 'P') {
            buf.resetReaderIndex();
            return Optional.empty();
        }

        int length = buf.readInt();
        if (length < 4) {
            buf.resetReaderIndex();
            return Optional.empty();
        }

        // Skip statement name (C-string)
        while (buf.readableBytes() > 0 && buf.readByte() != 0) {}

        // Read query (C-string)
        StringBuilder sb = new StringBuilder();
        while (buf.readableBytes() > 0) {
            byte b = buf.readByte();
            if (b == 0) break;
            sb.append((char) b);
        }

        buf.resetReaderIndex();
        String sql = sb.toString();
        log.debug("Parsed Extended Query: {}", sql.substring(0, Math.min(100, sql.length())));

        return Optional.of(sql);
    }
}
