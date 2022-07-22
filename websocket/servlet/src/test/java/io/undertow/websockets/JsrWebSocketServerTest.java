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
package io.undertow.websockets;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.undertow.server.handlers.PathHandler;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.testutils.AjpIgnore;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpOneOnly;
import io.undertow.util.NetworkUtils;
import io.undertow.websockets.jsr.test.FrameChecker;
import io.undertow.websockets.jsr.test.ProgramaticErrorEndpoint;
import io.undertow.websockets.jsr.test.WebSocketTestClient;
import io.undertow.websockets.jsr.test.annotated.AnnotatedClientEndpoint;
import io.undertow.websockets.servlet.JsrWebSocketFilter;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.SendHandler;
import jakarta.websocket.SendResult;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpointConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author <a href="mailto:nmaurer@redhat.com">Norman Maurer</a>
 */
@RunWith(DefaultServer.class)
@AjpIgnore
@HttpOneOnly
public class JsrWebSocketServerTest {

    @Test
    public void testBinaryWithByteBuffer() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Throwable> cause = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture latch = new CompletableFuture<>();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Whole<ByteBuffer>() {
                    @Override
                    public void onMessage(ByteBuffer message) {
                        ByteBuffer buf = ByteBuffer.allocate(message.remaining());
                        buf.put(message);
                        buf.flip();
                        session.getAsyncRemote().sendBinary(buf);
                    }
                });
            }
        }

        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + NetworkUtils.formatPossibleIpv6Address(DefaultServer.getHostAddress("default")) + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(BinaryWebSocketFrame.class, payload, latch));
        latch.get();
        Assert.assertNull(cause.get());
        client.destroy();
    }

    private ServerWebSocketContainer createContainer() {
        return new ServerWebSocketContainer(TestClassIntrospector.INSTANCE, getClass().getClassLoader(), DefaultServer.getEventLoopSupplier(), Collections.EMPTY_LIST, false, null, null, () -> GlobalEventExecutor.INSTANCE, Collections.emptyList(), Integer.MAX_VALUE, null);
    }

    @Test
    public void testBinaryWithByteArray() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Throwable> cause = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();
        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Whole<byte[]>() {
                    @Override
                    public void onMessage(byte[] message) {
                        session.getAsyncRemote().sendBinary(ByteBuffer.wrap(message.clone()));
                    }
                });
            }
        }
        ServerWebSocketContainer builder = createContainer();
        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());

        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(BinaryWebSocketFrame.class, payload, latch));
        latch.get();
        Assert.assertNull(cause.get());
        client.destroy();
    }

    @Test
    public void testText() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Throwable> cause = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Whole<String>() {
                    @Override
                    public void onMessage(String message) {
                        session.getAsyncRemote().sendText(message);
                    }
                });
            }
        }
        ServerWebSocketContainer builder = createContainer();
        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, payload, latch));
        latch.get();
        Assert.assertNull(cause.get());
        client.destroy();
    }

    @Test
    public void testBinaryWithByteBufferByCompletion() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<SendResult> sendResult = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();
        final CompletableFuture<?> latch2 = new CompletableFuture<>();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Whole<ByteBuffer>() {
                    @Override
                    public void onMessage(ByteBuffer message) {
                        ByteBuffer buf = ByteBuffer.allocate(message.remaining());
                        buf.put(message);
                        buf.flip();
                        session.getAsyncRemote().sendBinary(buf, new SendHandler() {
                            @Override
                            public void onResult(SendResult result) {
                                sendResult.set(result);
                                if (result.getException() != null) {
                                    latch2.completeExceptionally(new IOException(result.getException()));
                                } else {
                                    latch2.complete(null);
                                }
                            }
                        });
                    }
                });
            }
        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(BinaryWebSocketFrame.class, payload, latch));
        latch.get();
        latch2.get();

        SendResult result = sendResult.get();
        Assert.assertNotNull(result);
        Assert.assertNull(result.getException());

        client.destroy();
    }

    @Test
    public void testTextByCompletion() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<SendResult> sendResult = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();
        final CompletableFuture<?> latch2 = new CompletableFuture<>();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Whole<String>() {
                    @Override
                    public void onMessage(String message) {
                        session.getAsyncRemote().sendText(message, new SendHandler() {
                            @Override
                            public void onResult(SendResult result) {
                                sendResult.set(result);
                                if (result.getException() != null) {
                                    latch2.completeExceptionally(new IOException(result.getException()));
                                } else {
                                    latch2.complete(null);
                                }
                            }
                        });
                    }
                });
            }
        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());

        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, payload, latch));
        latch.get();
        latch2.get();

        SendResult result = sendResult.get();
        Assert.assertNotNull(result);
        Assert.assertNull(result.getException());

        client.destroy();
    }

    @Test
    public void testBinaryWithByteBufferByFuture() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Future<Void>> sendResult = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Whole<ByteBuffer>() {
                    @Override
                    public void onMessage(ByteBuffer message) {
                        ByteBuffer buf = ByteBuffer.allocate(message.remaining());
                        buf.put(message);
                        buf.flip();
                        sendResult.set(session.getAsyncRemote().sendBinary(buf));
                    }
                });
            }

        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(BinaryWebSocketFrame.class, payload, latch));
        latch.get();

        Future<Void> result = sendResult.get();

        client.destroy();
    }

    @Test
    public void testTextByFuture() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Future<Void>> sendResult = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Whole<String>() {
                    @Override
                    public void onMessage(String message) {
                        sendResult.set(session.getAsyncRemote().sendText(message));
                    }
                });
            }
        }
        ServerWebSocketContainer builder = createContainer();
        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, payload, latch));
        latch.get();

        sendResult.get();

        client.destroy();
    }

    @Test
    public void testBinaryWithByteArrayUsingStream() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Throwable> cause = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();
        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Whole<byte[]>() {
                    @Override
                    public void onMessage(final byte[] message) {
                        DefaultServer.getUndertow().getWorker().execute(new Runnable() {
                            @Override
                            public void run() {

                                try {
                                    OutputStream out = session.getBasicRemote().getSendStream();
                                    out.write(message);
                                    out.flush();
                                    out.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    cause.set(e);
                                    latch.completeExceptionally(e);
                                }
                            }
                        });
                    }
                });
            }
        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());

        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(BinaryWebSocketFrame.class, payload, latch));
        latch.get();
        Assert.assertNull(cause.get());
        client.destroy();
    }

    @Test
    public void testTextUsingWriter() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Throwable> cause = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Whole<String>() {
                    @Override
                    public void onMessage(final String message) {
                        DefaultServer.getUndertow().getWorker().execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Writer writer = session.getBasicRemote().getSendWriter();
                                    writer.write(message);
                                    writer.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    cause.set(e);
                                    latch.completeExceptionally(e);
                                }
                            }
                        });
                    }
                });
            }
        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, payload, latch));
        latch.get();
        Assert.assertNull(cause.get());
        client.destroy();
    }

    @Test
    @Ignore("UT3 - P4")
    public void testPingPong() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Throwable> cause = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
            }
        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new PingWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(PongWebSocketFrame.class, payload, latch));
        latch.get(10, TimeUnit.SECONDS);
        Assert.assertNull(cause.get());
        client.destroy();
    }

    @Test
    public void testCloseFrame() throws Exception {
        final int code = 1000;
        final String reasonText = "TEST";
        final AtomicReference<CloseReason> reason = new AtomicReference<>();
        ByteBuffer payload = ByteBuffer.allocate(reasonText.length() + 2);
        payload.putShort((short) code);
        payload.put(reasonText.getBytes("UTF-8"));
        payload.flip();

        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();
        final CountDownLatch clientLatch = new CountDownLatch(1);
        final AtomicInteger closeCount = new AtomicInteger();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
            }

            @Override
            public void onClose(Session session, CloseReason closeReason) {
                closeCount.incrementAndGet();
                reason.set(closeReason);
                clientLatch.countDown();
            }
        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new CloseWebSocketFrame(code, reasonText), new FrameChecker(CloseWebSocketFrame.class, payload.array(), latch));
        latch.get();
        clientLatch.await();
        Assert.assertEquals(code, reason.get().getCloseCode().getCode());
        Assert.assertEquals(reasonText, reason.get().getReasonPhrase());
        Assert.assertEquals(1, closeCount.get());
        client.destroy();
    }

    /**
     * Section 5.5.1 of RFC 6455 says the reason body is optional
     */
    @Test
    public void testCloseFrameWithoutReasonBody() throws Exception {
        final int code = 1000;
        final AtomicReference<CloseReason> reason = new AtomicReference<>();
        ByteBuffer payload = ByteBuffer.allocate(2);
        payload.putShort((short) code);
        payload.flip();

        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();
        final CountDownLatch clientLatch = new CountDownLatch(1);
        final AtomicInteger closeCount = new AtomicInteger();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
            }

            @Override
            public void onClose(Session session, CloseReason closeReason) {
                closeCount.incrementAndGet();
                reason.set(closeReason);
                clientLatch.countDown();
            }
        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new CloseWebSocketFrame(code, null), new FrameChecker(CloseWebSocketFrame.class, payload.array(), latch));
        latch.get(10, TimeUnit.SECONDS);
        clientLatch.await();
        Assert.assertEquals(code, reason.get().getCloseCode().getCode());
        Assert.assertEquals("", reason.get().getReasonPhrase());
        Assert.assertEquals(1, closeCount.get());
        client.destroy();
    }

    @Test
    public void testBinaryWithByteBufferAsync() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Throwable> cause = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();

        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Partial<ByteBuffer>() {
                    @Override
                    public void onMessage(ByteBuffer message, boolean last) {
                        Assert.assertTrue(last);
                        ByteBuffer buf = ByteBuffer.allocate(message.remaining());
                        buf.put(message);
                        buf.flip();
                        session.getAsyncRemote().sendBinary(buf);

                    }
                });
            }
        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(BinaryWebSocketFrame.class, payload, latch));
        latch.get();
        Assert.assertNull(cause.get());
        client.destroy();
    }

    @Test
    public void testTextAsync() throws Exception {
        final byte[] payload = "payload".getBytes();
        final AtomicReference<Throwable> cause = new AtomicReference<>();
        final AtomicBoolean connected = new AtomicBoolean(false);
        final CompletableFuture<?> latch = new CompletableFuture<>();
        class TestEndPoint extends Endpoint {
            @Override
            public void onOpen(final Session session, EndpointConfig config) {
                connected.set(true);
                session.addMessageHandler(new MessageHandler.Partial<String>() {

                    StringBuilder sb = new StringBuilder();

                    @Override
                    public void onMessage(String message, boolean last) {
                        sb.append(message);
                        if (!last) {
                            return;
                        }
                        session.getAsyncRemote().sendText(sb.toString());
                    }
                });
            }
        }
        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(TestEndPoint.class, "/").configurator(new InstanceConfigurator(new TestEndPoint())).build());
        deployServlet(builder);

        WebSocketTestClient client = new WebSocketTestClient(new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));
        client.connect();
        client.send(new TextWebSocketFrame(Unpooled.wrappedBuffer(payload)), new FrameChecker(TextWebSocketFrame.class, payload, latch));
        latch.get();
        Assert.assertNull(cause.get());
        client.destroy();
    }


    @Test
    @Ignore("UT3 - P2")
    public void testErrorHandling() throws Exception {


        ServerWebSocketContainer builder = createContainer();

        builder.addEndpoint(ServerEndpointConfig.Builder.create(ProgramaticErrorEndpoint.class, "/").configurator(new InstanceConfigurator(new ProgramaticErrorEndpoint())).build());
        deployServlet(builder);

        AnnotatedClientEndpoint c = new AnnotatedClientEndpoint();

        Session session = ContainerProvider.getWebSocketContainer().connectToServer(c, new URI("ws://" + DefaultServer.getHostAddress("default") + ":" + DefaultServer.getHostPort("default") + "/"));

        Assert.assertEquals("hi", ProgramaticErrorEndpoint.getMessage());
        session.getAsyncRemote().sendText("app-error");
        Assert.assertEquals("app-error", ProgramaticErrorEndpoint.getMessage());
        Assert.assertEquals("ERROR: java.lang.RuntimeException", ProgramaticErrorEndpoint.getMessage());
        Assert.assertTrue(c.isOpen());

        session.getBasicRemote().sendText("io-error");
        Assert.assertEquals("io-error", ProgramaticErrorEndpoint.getMessage());
        Assert.assertEquals("ERROR: java.lang.RuntimeException", ProgramaticErrorEndpoint.getMessage());
        Assert.assertTrue(c.isOpen());
        ((UndertowSession) session).forceClose();
        Assert.assertEquals("CLOSED", ProgramaticErrorEndpoint.getMessage());

    }

    private ServletContext deployServlet(final ServerWebSocketContainer deployment) throws ServletException {

        final DeploymentInfo builder;
        builder = new DeploymentInfo()
                .setClassLoader(getClass().getClassLoader())
                .setContextPath("/")
                .setClassIntrospecter(io.undertow.servlet.test.util.TestClassIntrospector.INSTANCE)
                .setDeploymentName("websocket.war")
                .addFilter(new FilterInfo("filter", JsrWebSocketFilter.class))
                .addFilterUrlMapping("filter", "/*", DispatcherType.REQUEST)
                .addServletContextAttribute(jakarta.websocket.server.ServerContainer.class.getName(), deployment);

        final PathHandler root = new PathHandler();
        final ServletContainer container = ServletContainer.Factory.newInstance();
        DeploymentManager manager = container.addDeployment(builder);
        manager.deploy();
        root.addPrefixPath(builder.getContextPath(), manager.start());

        DefaultServer.setRootHandler(root);
        return manager.getDeployment().getServletContext();
    }

    private static class InstanceConfigurator extends ServerEndpointConfig.Configurator {

        private final Object endpoint;

        private InstanceConfigurator(final Object endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public <T> T getEndpointInstance(final Class<T> endpointClass) throws InstantiationException {
            return (T) endpoint;
        }
    }

}
