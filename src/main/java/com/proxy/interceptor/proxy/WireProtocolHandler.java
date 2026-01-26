package com.proxy.interceptor.proxy;

import io.netty.buffer.ByteBuf;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.swing.text.html.Option;
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
        try {
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

            // Remaining bytes after reading 4 byte length
            int remainingBytes = length - 4;
            if (buf.readableBytes() < remainingBytes) {
                buf.resetReaderIndex();
                return Optional.empty();
            }

            // Prevent reading into next message
            int messageEndIndex = buf.readerIndex() + remainingBytes;

            // Skip statement name (C-string)
            if (!skipCString(buf, messageEndIndex)) {
                buf.resetReaderIndex();
                return Optional.empty();
            }

            // Read query (C-string) as UTF-8 bytes
            Optional<String> sqlOpt = readCStringUtf8(buf, messageEndIndex);
            if (sqlOpt.isEmpty()) {
                buf.resetReaderIndex();
                return Optional.empty();
            }

            String sql = sqlOpt.get();

            buf.resetReaderIndex();

            log.debug("Parsed Extended Query: {}", sql.substring(0, Math.min(100, sql.length())));

            return Optional.of(sql);
        } catch (IndexOutOfBoundsException e) {
            buf.resetReaderIndex();
            return Optional.empty();
        }
    }

    /*
    * Skips a null-terminated. C-string within (currentReaderIndex, messageEndIndex).
    * Returns true if we found a null terminator, false otherwise.
     */
    private boolean skipCString(ByteBuf buf, int messageEndIndex) {
        while (buf.readerIndex() < messageEndIndex) {
            if (buf.readByte() == 0) {
                return true; // null-terminator
            }
        }
        return false; // null-terminator not found
    }

    /*
    * Reads a null-terminated C-string as UTF-8 within (currentReaderIndex, messageEndIndex).
    * Returns Optional.empty() if terminator not found.
     */
    private Optional<String> readCStringUtf8(ByteBuf buf, int messageEndIndex) {
        int start = buf.readerIndex();

        int end = start;
        while (end < messageEndIndex) {
            if (buf.getByte(end) == 0) {
                break;
            }
            end++;
        }

        if (end >= messageEndIndex) {
            return Optional.empty();
        }

        int len = end - start;

        byte[] bytes = new byte[len];
        buf.readBytes(bytes);

        buf.readByte();

        return Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }
}
