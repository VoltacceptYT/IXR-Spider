package net.browselotus.spiderbot.resourcepack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultFileRegion;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Lets the pack be downloaded over the Minecraft server's own port.
 *
 * Hooks the server's listening channel; every new connection gets a "sniffer" as its first
 * handler. If the first bytes look like an HTTP GET/HEAD, the sniffer strips the Minecraft
 * handlers from that one connection and answers the request itself. Anything else is passed
 * straight through and the sniffer removes itself, so normal play is unaffected.
 */
final class GamePortShare {

    private static final String ACCEPTOR_NAME = "spiderbot_pack_acceptor";
    private static final String SNIFFER_NAME = "spiderbot_pack_sniffer";
    private static final int MAX_REQUEST_BYTES = 8192;

    private final PackSender plugin;
    private final List<Channel> serverChannels = new ArrayList<>();

    GamePortShare(PackSender plugin) {
        this.plugin = plugin;
    }

    void inject() throws Exception {
        List<ChannelFuture> futures = findServerChannelFutures();
        if (futures.isEmpty()) {
            throw new IllegalStateException("no server channels found");
        }
        for (ChannelFuture future : futures) {
            Channel channel = future.channel();
            channel.pipeline().addFirst(ACCEPTOR_NAME, new Acceptor());
            serverChannels.add(channel);
        }
    }

    void close() {
        for (Channel channel : serverChannels) {
            try {
                channel.pipeline().remove(ACCEPTOR_NAME);
            } catch (Exception ignored) {
                // already gone
            }
        }
        serverChannels.clear();
    }

    // ------------------------------------------------------------- reflection

    /** Finds the server's listening channels without compiling against server internals. */
    private static List<ChannelFuture> findServerChannelFutures() throws Exception {
        Object craftServer = Bukkit.getServer();
        Object minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);

        Object connection = null;
        for (Method method : minecraftServer.getClass().getMethods()) {
            if (method.getParameterCount() == 0
                    && method.getReturnType().getSimpleName().equals("ServerConnectionListener")) {
                connection = method.invoke(minecraftServer);
                break;
            }
        }
        if (connection == null) {
            throw new IllegalStateException("ServerConnectionListener not found");
        }

        List<ChannelFuture> result = new ArrayList<>();
        for (Class<?> c = connection.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(connection);
                if (value instanceof List<?> list) {
                    synchronized (list) {
                        for (Object o : list) {
                            if (o instanceof ChannelFuture cf) {
                                result.add(cf);
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    // --------------------------------------------------------------- handlers

    /** Sits on the listening channel and tags every accepted connection with a sniffer. */
    private final class Acceptor extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel child) {
                child.pipeline().addFirst(SNIFFER_NAME, new Sniffer());
            }
            ctx.fireChannelRead(msg);
        }
    }

    private final class Sniffer extends ChannelInboundHandlerAdapter {
        private final StringBuilder request = new StringBuilder();
        private boolean http;
        private boolean responded;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!http) {
                if (msg instanceof ByteBuf buf && (startsWith(buf, "GET ") || startsWith(buf, "HEAD "))) {
                    http = true;
                    takeOver(ctx);
                } else {
                    // Normal Minecraft traffic: get out of the way.
                    ctx.pipeline().remove(this);
                    ctx.fireChannelRead(msg);
                    return;
                }
            }

            ByteBuf buf = (ByteBuf) msg;
            try {
                request.append(buf.toString(StandardCharsets.ISO_8859_1));
            } finally {
                buf.release();
            }

            if (request.length() > MAX_REQUEST_BYTES) {
                ctx.close();
                return;
            }
            if (!responded && request.indexOf("\r\n\r\n") >= 0) {
                responded = true;
                respond(ctx);
            }
        }

        /** Removes every other handler so Minecraft's decoders never see the HTTP bytes. */
        private void takeOver(ChannelHandlerContext ctx) {
            for (Map.Entry<String, ChannelHandler> entry : ctx.pipeline()) {
                if (entry.getValue() != this) {
                    ctx.pipeline().remove(entry.getValue());
                }
            }
            // Drop connections that never finish sending a request.
            ctx.executor().schedule(() -> {
                if (!responded) {
                    ctx.close();
                }
            }, 15, TimeUnit.SECONDS);
        }

        private void respond(ChannelHandlerContext ctx) {
            String line = request.substring(0, request.indexOf("\r\n"));
            String[] parts = line.split(" ");
            if (parts.length < 2) {
                sendStatus(ctx, 400, "Bad Request");
                return;
            }

            String method = parts[0];
            boolean head = method.equals("HEAD");
            if (!head && !method.equals("GET")) {
                sendStatus(ctx, 405, "Method Not Allowed");
                return;
            }

            String target = parts[1];
            int query = target.indexOf('?');
            String path = query >= 0 ? target.substring(0, query) : target;

            Path file = plugin.resolveRequest(path);
            long size;
            try {
                size = file == null ? -1 : Files.size(file);
            } catch (Exception e) {
                size = -1;
            }
            if (size < 0) {
                sendStatus(ctx, 404, "Not Found");
                return;
            }

            String header = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: "
                    + size + "\r\nConnection: close\r\n\r\n";
            ByteBuf headerBuf = Unpooled.copiedBuffer(header, StandardCharsets.ISO_8859_1);

            if (head) {
                ctx.writeAndFlush(headerBuf).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            ctx.write(headerBuf);
            ctx.writeAndFlush(new DefaultFileRegion(file.toFile(), 0, size))
                    .addListener(ChannelFutureListener.CLOSE);
        }

        private void sendStatus(ChannelHandlerContext ctx, int code, String reason) {
            String header = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            ctx.writeAndFlush(Unpooled.copiedBuffer(header, StandardCharsets.ISO_8859_1))
                    .addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }

    private static boolean startsWith(ByteBuf buf, String prefix) {
        if (buf.readableBytes() < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (buf.getByte(buf.readerIndex() + i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
