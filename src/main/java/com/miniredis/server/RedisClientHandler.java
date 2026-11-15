package com.miniredis.server;

import com.miniredis.commands.CommandRegistry;
import com.miniredis.commands.RedisResponse;
import com.miniredis.protocol.RespParser;
import com.miniredis.protocol.RespProtocolException;
import com.miniredis.protocol.RespSerializer;
import com.miniredis.storage.ByteSequence;
import com.miniredis.storage.ServerMetrics;
import com.miniredis.storage.StorageEngine;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Handles communication with a single TCP client connection.
 * Automatically supports both wire-level RESP2 protocol (for official redis-cli, redis-py, Jedis, Lettuce)
 * and CRLF-terminated inline commands (for telnet, netcat, and legacy scripts).
 * Maintains per-connection client metadata and optimistic transaction state (MULTI/EXEC/WATCH).
 */
public class RedisClientHandler implements Runnable, AutoCloseable {

    private static final AtomicLong FALLBACK_ID_SEQ = new AtomicLong(1);

    private final long clientId;
    private final Socket socket;
    private final StorageEngine storage;
    private final CommandRegistry registry;
    private final Consumer<RedisClientHandler> onDisconnect;
    private final TransactionContext transactionContext = new TransactionContext();

    private final long connectedAtMillis;
    private volatile long lastInteractionMillis;
    private volatile String clientName = "";
    private volatile String lastCommandName = "none";
    private volatile boolean running = true;

    public RedisClientHandler(Socket socket, StorageEngine storage, CommandRegistry registry,
                              Consumer<RedisClientHandler> onDisconnect) {
        this(FALLBACK_ID_SEQ.getAndIncrement(), socket, storage, registry, onDisconnect);
    }

    public RedisClientHandler(long clientId, Socket socket, StorageEngine storage, CommandRegistry registry,
                              Consumer<RedisClientHandler> onDisconnect) {
        this.clientId = clientId;
        this.socket = Objects.requireNonNull(socket, "socket cannot be null");
        this.storage = Objects.requireNonNull(storage, "storage cannot be null");
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.onDisconnect = onDisconnect;
        long now = storage.now();
        this.connectedAtMillis = now;
        this.lastInteractionMillis = now;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
        } catch (SocketException ignored) {}

        try (InputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

            while (running && !socket.isClosed()) {
                RespParser.CommandPayload payload;
                try {
                    payload = RespParser.readCommand(in);
                } catch (RespProtocolException e) {
                    storage.incrementFailedOperations();
                    RedisResponse err = RedisResponse.error("ERR Protocol error: " + e.getMessage());
                    out.write(RespSerializer.serializeResponse(err));
                    out.flush();
                    break;
                } catch (SocketException e) {
                    // Socket was closed locally or remotely
                    break;
                } catch (EOFException e) {
                    break;
                }

                if (payload == null) {
                    // Client reached EOF / closed connection
                    break;
                }

                List<ByteSequence> rawTokens = payload.getRawTokens();
                if (rawTokens == null || rawTokens.isEmpty()) {
                    continue;
                }

                lastInteractionMillis = storage.now();
                String cmdName = rawTokens.get(0).toUtf8String().toUpperCase(Locale.ROOT);
                lastCommandName = cmdName.toLowerCase(Locale.ROOT);

                if (cmdName.equals("QUIT") || cmdName.equals("EXIT")) {
                    transactionContext.resetAfterExecOrDiscard();
                    if (payload.isRespProtocol()) {
                        out.write(RespSerializer.serializeResponse(RedisResponse.ok()));
                    } else {
                        out.write("OK\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                    break;
                }

                boolean wasInMulti = transactionContext.isInMulti();
                RedisResponse response = dispatchWithClientSessionBytes(rawTokens, cmdName);
                if ("SHUTDOWN".equals(cmdName) && !wasInMulti
                        && response.getType() != RedisResponse.ResponseType.ERROR) {
                    // Redis protocol: SHUTDOWN closes the client connection silently on success
                    running = false;
                    break;
                }
                if (payload.isRespProtocol()) {
                    out.write(RespSerializer.serializeResponse(response));
                } else {
                    out.write(response.toInlineResponse().getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        } catch (IOException e) {
            // Expected upon disconnect or socket closure
        } finally {
            transactionContext.resetAfterExecOrDiscard();
            close();
            if (onDisconnect != null) {
                onDisconnect.accept(this);
            }
        }
    }

    private RedisResponse dispatchWithClientSessionBytes(List<ByteSequence> tokens, String cmdName) {
        if ("CLIENT".equals(cmdName) && !transactionContext.isInMulti() && tokens.size() >= 2) {
            String sub = tokens.get(1).toUtf8String().toUpperCase(Locale.ROOT);
            if ("SETNAME".equals(sub) && tokens.size() == 3) {
                this.clientName = tokens.get(2).toUtf8String();
                return registry.dispatchBytes(tokens, storage, transactionContext);
            }
            if ("GETNAME".equals(sub) && tokens.size() == 2) {
                storage.incrementCompletedOperations();
                return clientName.isEmpty() ? RedisResponse.nil() : RedisResponse.bulkString(clientName);
            }
        }
        return registry.dispatchBytes(tokens, storage, transactionContext);
    }

    public ServerMetrics.ClientSnapshot toSnapshot(long nowMillis) {
        String addr = socket.getRemoteSocketAddress() != null
                ? socket.getRemoteSocketAddress().toString().replaceFirst("^/", "")
                : "unknown";
        long ageSec = Math.max(0L, (nowMillis - connectedAtMillis) / 1000L);
        long idleSec = Math.max(0L, (nowMillis - lastInteractionMillis) / 1000L);
        return new ServerMetrics.ClientSnapshot(
                clientId,
                addr,
                clientName,
                ageSec,
                idleSec,
                transactionContext.getMultiQueueDepth(),
                lastCommandName
        );
    }

    public TransactionContext getTransactionContext() {
        return transactionContext;
    }

    @Override
    public void close() {
        running = false;
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }

    public boolean isRunning() {
        return running && !socket.isClosed();
    }

    public Socket getSocket() {
        return socket;
    }
}
