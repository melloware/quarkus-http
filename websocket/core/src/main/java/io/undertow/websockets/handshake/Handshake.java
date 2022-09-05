/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.websockets.handshake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import jakarta.websocket.Extension;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionDecoder;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionEncoder;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionUtil;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameServerExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.undertow.websockets.ConfiguredServerEndpoint;
import io.undertow.websockets.ExtensionImpl;

/**
 * Abstract base class for doing a WebSocket Handshake.
 *
 * @author Mike Brock
 */
public class Handshake {


    private static final String EXTENSION_SEPARATOR = ",";
    private static final String PARAMETER_SEPARATOR = ";";
    private static final char PARAMETER_EQUAL = '=';

    public static final String MAGIC_NUMBER = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    public static final String SHA1 = "SHA1";
    private static final String WEB_SOCKET_VERSION = "13";

    protected final Set<String> subprotocols;
    private static final byte[] EMPTY = new byte[0];
    private static final Pattern PATTERN = Pattern.compile("\\s*,\\s*");
    protected Set<WebSocketServerExtensionHandshaker> availableExtensions = new HashSet<>(Arrays.asList(new DeflateFrameServerExtensionHandshaker(), new PerMessageDeflateServerExtensionHandshaker()));
    protected boolean allowExtensions;
    private final ConfiguredServerEndpoint config;
    private final int maxFrameSize;

    public Handshake(ConfiguredServerEndpoint config, final Set<String> subprotocols, int maxFrameSize) {
        this.subprotocols = subprotocols;
        this.config = config;
        this.maxFrameSize = maxFrameSize;
    }

    public ConfiguredServerEndpoint getConfig() {
        return config;
    }

    /**
     * Return the full url of the websocket location of the given {@link WebSocketHttpExchange}
     */
    protected static String getWebSocketLocation(WebSocketHttpExchange exchange) {
        String scheme;
        if ("https".equals(exchange.getRequestScheme())) {
            scheme = "wss";
        } else {
            scheme = "ws";
        }
        return scheme + "://" + exchange.getRequestHeader(HttpHeaderNames.HOST) + exchange.getRequestURI();
    }

    /**
     * Issue the WebSocket upgrade
     *
     * @param exchange The {@link WebSocketHttpExchange} for which the handshake and upgrade should occur.
     */
    public final void handshake(final WebSocketHttpExchange exchange, Consumer<ChannelHandlerContext> completeListener) {
        String origin = exchange.getRequestHeader(HttpHeaderNames.ORIGIN);
        if (origin != null) {
            exchange.setResponseHeader(HttpHeaderNames.ORIGIN, origin);
        }
        selectSubprotocol(exchange);
        List<WebSocketServerExtension> extensions = selectExtensions(exchange);
        exchange.setResponseHeader(HttpHeaderNames.SEC_WEBSOCKET_LOCATION, getWebSocketLocation(exchange));

        final String key = exchange.getRequestHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY);
        try {
            final String solution = solve(key);
            exchange.setResponseHeader(HttpHeaderNames.SEC_WEBSOCKET_ACCEPT, solution);
            performUpgrade(exchange);
        } catch (NoSuchAlgorithmException e) {
            exchange.endExchange();
            return;
        }

        handshakeInternal(exchange);
        exchange.upgradeChannel(new Consumer<Object>() {
            @Override
            public void accept(Object c) {
                ChannelHandlerContext context = (ChannelHandlerContext) c;

                WebSocket13FrameDecoder decoder = new WebSocket13FrameDecoder(true, !extensions.isEmpty(), maxFrameSize, false);
                WebSocket13FrameEncoder encoder = new WebSocket13FrameEncoder(false);
                ChannelPipeline p = context.pipeline();
                if (p.get(HttpObjectAggregator.class) != null) {
                    p.remove(HttpObjectAggregator.class);
                }
                if (p.get(HttpContentCompressor.class) != null) {
                    p.remove(HttpContentCompressor.class);
                }
                p.addLast("ws-encoder", encoder);
                p.addLast("ws-decoder", decoder);
                for(WebSocketServerExtension extension : extensions) {
                    WebSocketExtensionDecoder exdecoder = extension.newExtensionDecoder();
                    WebSocketExtensionEncoder exencoder = extension.newExtensionEncoder();
                    p.addAfter("ws-decoder", exdecoder.getClass().getName(), exdecoder);
                    p.addAfter("ws-encoder", exencoder.getClass().getName(), exencoder);
                }

                completeListener.accept(context);
            }
        });
    }

    protected void handshakeInternal(final WebSocketHttpExchange exchange) {
    }

    /**
     * convenience method to perform the upgrade
     */
    protected final void performUpgrade(final WebSocketHttpExchange exchange) {
        exchange.setResponseHeader(HttpHeaderNames.UPGRADE, "WebSocket");
        exchange.setResponseHeader(HttpHeaderNames.CONNECTION, "Upgrade");
        HandshakeUtil.prepareUpgrade(config.getEndpointConfiguration(), exchange);
    }

    /**
     * Selects the first matching supported sub protocol and add it the the headers of the exchange.
     */
    protected final void selectSubprotocol(final WebSocketHttpExchange exchange) {
        String requestedSubprotocols = exchange.getRequestHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
        if (requestedSubprotocols == null) {
            return;
        }
        if (requestedSubprotocols.trim().isEmpty()) {
            throw new RuntimeException(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL + " header was provided but was empty");
        }

        String[] requestedSubprotocolArray = PATTERN.split(requestedSubprotocols);
        String subProtocol = supportedSubprotols(requestedSubprotocolArray);
        if (subProtocol != null && !subProtocol.isEmpty()) {
            exchange.setResponseHeader(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, subProtocol);
        }

    }


    final List<WebSocketServerExtension> selectExtensions(final WebSocketHttpExchange exchange) {
        String extensionHeader = exchange.getRequestHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
        if(extensionHeader == null) {
            return Collections.emptyList();
        }
        if (extensionHeader.trim().isEmpty()) {
            throw new RuntimeException(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS + " header was provided but was empty");
        }
        List<WebSocketExtensionData> requestedExtensions = WebSocketExtensionUtil.extractExtensions(extensionHeader);
        List<WebSocketServerExtension> extensions = selectedExtension(requestedExtensions);
        if (extensions != null && !extensions.isEmpty()) {
            String headerValue = "";
            for (WebSocketServerExtension extension : extensions) {
                WebSocketExtensionData extensionData = extension.newReponseData();
                headerValue = appendExtension(headerValue,
                        extensionData.name(), extensionData.parameters());
            }
            exchange.setResponseHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, headerValue);
        }
        return extensions;
    }

    /**
     * Add a new WebSocket Extension handshake to the list of available extensions.
     *
     * @param extension a new {@code ExtensionHandshake}
     */
    public final void addExtension(WebSocketServerExtensionHandshaker extension) {
        availableExtensions.add(extension);
        allowExtensions = true;
    }

    /**
     * Create the {@code ExtensionFunction} list associated with the negotiated extensions defined in the exchange's response.
     *
     * @param exchange the exchange used to retrieve negotiated extensions
     * @return a list of {@code ExtensionFunction} with the implementation of the extensions
     */
    protected final List<WebSocketServerExtension> initExtensions(final WebSocketHttpExchange exchange) {
        String extHeader = exchange.getResponseHeader(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);

        List<WebSocketServerExtension> ret = new ArrayList<>();
        if (extHeader != null) {
            List<WebSocketExtensionData> extensions = WebSocketExtensionUtil.extractExtensions(extHeader);
            if (extensions != null && !extensions.isEmpty()) {
                for (WebSocketExtensionData ext : extensions) {
                    for (WebSocketServerExtensionHandshaker extHandshake : availableExtensions) {
                        WebSocketServerExtension negotiated = extHandshake.handshakeExtension(ext);
                        if (negotiated != null) {
                            ret.add(negotiated);
                        }
                    }
                }
            }
        }
        return ret;
    }

    protected String supportedSubprotols(String[] requestedSubprotocolArray) {
        return HandshakeUtil.selectSubProtocol(config, requestedSubprotocolArray);
    }

    protected List<WebSocketServerExtension> selectedExtension(List<WebSocketExtensionData> extensionList) {
        List<Extension> ext = new ArrayList<>();
        for (WebSocketExtensionData i : extensionList) {
            ext.add(new ExtensionImpl(i));
        }
        List<Extension> selected = HandshakeUtil.selectExtensions(config, ext);
        if (selected == null) {
            return Collections.emptyList();
        }
        List<WebSocketServerExtension> ret = new ArrayList<>();
        for (Extension i : selected) {
            for (WebSocketServerExtensionHandshaker handshaker : availableExtensions) {
                WebSocketServerExtension negotiated = handshaker.handshakeExtension(((ExtensionImpl) i).getData());
                if (negotiated != null) {
                    ret.add(negotiated);
                    break;
                }
            }
            if (!ret.isEmpty()) {
                //we only have two extensions at the moment
                //and they are both RSV1
                break;
            }
        }

        return ret;
    }


    static String appendExtension(String currentHeaderValue, String extensionName,
                                  Map<String, String> extensionParameters) {

        StringBuilder newHeaderValue = new StringBuilder(
                currentHeaderValue != null ? currentHeaderValue.length() : extensionName.length() + 1);
        if (currentHeaderValue != null && !currentHeaderValue.trim().isEmpty()) {
            newHeaderValue.append(currentHeaderValue);
            newHeaderValue.append(EXTENSION_SEPARATOR);
        }
        newHeaderValue.append(extensionName);
        for (Map.Entry<String, String> extensionParameter : extensionParameters.entrySet()) {
            newHeaderValue.append(PARAMETER_SEPARATOR);
            newHeaderValue.append(extensionParameter.getKey());
            if (extensionParameter.getValue() != null) {
                newHeaderValue.append(PARAMETER_EQUAL);
                newHeaderValue.append(extensionParameter.getValue());
            }
        }
        return newHeaderValue.toString();
    }

    public boolean matches(final WebSocketHttpExchange exchange) {
        if (exchange.getRequestHeader(HttpHeaderNames.SEC_WEBSOCKET_KEY) != null &&
                exchange.getRequestHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION) != null) {
            if (exchange.getRequestHeader(HttpHeaderNames.SEC_WEBSOCKET_VERSION)
                    .equals(WEB_SOCKET_VERSION)) {
                return HandshakeUtil.checkOrigin(config.getEndpointConfiguration(), exchange);
            }
        }
        return false;
    }

    protected final String solve(final String nonceBase64) throws NoSuchAlgorithmException {
        final String concat = nonceBase64.trim() + MAGIC_NUMBER;
        final MessageDigest digest = MessageDigest.getInstance(SHA1);
        digest.update(concat.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeBytes(digest.digest()).trim();
    }

}
