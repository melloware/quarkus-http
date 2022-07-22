/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.websockets;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import io.undertow.websockets.util.ObjectIntrospecter;
import io.undertow.websockets.util.ImmediateObjectHandle;
import io.undertow.websockets.util.ObjectFactory;
import io.undertow.websockets.util.ObjectHandle;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * @author Stuart Douglas
 */
public class UndertowContainerProvider extends ContainerProvider {

    private static final boolean directBuffers = Boolean.getBoolean("io.undertow.websockets.direct-buffers");
    private static final boolean invokeInIoThread = Boolean.getBoolean("io.undertow.websockets.invoke-in-io-thread");

    private static final RuntimePermission PERMISSION = new RuntimePermission("io.undertow.websockets.jsr.MODIFY_WEBSOCKET_CONTAINER");

    private static final Map<ClassLoader, WebSocketContainer> webSocketContainers = new ConcurrentHashMap<>();

    private static volatile ServerWebSocketContainer defaultContainer;
    private static volatile boolean defaultContainerDisabled = false;
    private static volatile EventLoopGroup defaultEventLoopGroup;

    private static final SwitchableObjectIntrospector defaultIntrospector = new SwitchableObjectIntrospector();

    @Override
    protected WebSocketContainer getContainer() {
        ClassLoader tccl;
        if (System.getSecurityManager() == null) {
            tccl = Thread.currentThread().getContextClassLoader();
        } else {
            tccl = AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                @Override
                public ClassLoader run() {
                    return Thread.currentThread().getContextClassLoader();
                }
            });
        }
        WebSocketContainer webSocketContainer = webSocketContainers.get(tccl);
        if (webSocketContainer == null) {
            return getDefaultContainer();
        }
        return webSocketContainer;
    }

    static ServerWebSocketContainer getDefaultContainer() {
        if (defaultContainerDisabled) {
            return null;
        }
        if (defaultContainer != null) {
            return defaultContainer;
        }
        synchronized (UndertowContainerProvider.class) {
            if (defaultContainer == null) {
                //this is not great, as we have no way to control the lifecycle
                //but there is not much we can do
                Supplier<EventLoopGroup> supplier = new Supplier<EventLoopGroup>() {

                    @Override
                    public EventLoopGroup get() {
                        return getDefaultEventLoopGroup();
                    }
                };
                defaultContainer = new ServerWebSocketContainer(defaultIntrospector, UndertowContainerProvider.class.getClassLoader(), supplier, Collections.EMPTY_LIST, !invokeInIoThread, null, null, new Supplier<Executor>() {
                    @Override
                    public Executor get() {
                        return GlobalEventExecutor.INSTANCE;
                    }
                }, Collections.emptyList(), Integer.MAX_VALUE, null);
            }
            return defaultContainer;
        }
    }

    public static EventLoopGroup getDefaultEventLoopGroup() {
        if (defaultEventLoopGroup == null) {
            synchronized (UndertowContainerProvider.class) {
                if (defaultEventLoopGroup == null) {
                    defaultEventLoopGroup = new NioEventLoopGroup();
                }
            }
        }
        return defaultEventLoopGroup;
    }

    public static void addContainer(final ClassLoader classLoader, final WebSocketContainer webSocketContainer) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(PERMISSION);
        }
        webSocketContainers.put(classLoader, webSocketContainer);
    }

    public static void setDefaultContainer(ServerWebSocketContainer container) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(PERMISSION);
        }
        defaultContainer = container;
    }

    public static void removeContainer(final ClassLoader classLoader) {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(PERMISSION);
        }
        webSocketContainers.remove(classLoader);
    }

    public void setDefaultClassIntrospector(ObjectIntrospecter classIntrospector) {
        if (classIntrospector == null) {
            throw new IllegalArgumentException();
        }
        defaultIntrospector.setIntrospecter(classIntrospector);
    }

    public static void disableDefaultContainer() {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(PERMISSION);
        }
        defaultContainerDisabled = true;
    }


    private static class SwitchableObjectIntrospector implements ObjectIntrospecter {

        private volatile ObjectIntrospecter introspecter = new ObjectIntrospecter() {
            @Override
            public <T> ObjectFactory<T> createInstanceFactory(Class<T> clazz) {
                return new ObjectFactory<T>() {
                    @Override
                    public ObjectHandle<T> createInstance() {
                        try {
                            return new ImmediateObjectHandle<T>(clazz.newInstance());
                        } catch (InstantiationException | IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                    }
                };
            }
        };

        @Override
        public <T> ObjectFactory<T> createInstanceFactory(Class<T> clazz) {
            return introspecter.createInstanceFactory(clazz);
        }

        public void setIntrospecter(ObjectIntrospecter introspecter) {
            this.introspecter = introspecter;
        }
    }
}
