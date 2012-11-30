package org.jolokia.backend;

/*
 * Copyright 2009-2011 Roland Huss
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;

import javax.management.*;

import org.easymock.EasyMock;
import org.jolokia.detector.ServerHandle;
import org.jolokia.handler.JsonRequestHandler;
import org.jolokia.request.JmxRequest;
import org.jolokia.request.JmxRequestBuilder;
import org.jolokia.util.*;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.easymock.EasyMock.*;
import static org.testng.Assert.*;

/**
 * @author roland
 * @since 02.09.11
 */
public class MBeanServerHandlerTest {

    private JmxRequest request;

    private MBeanServerHandler handler;

    @BeforeMethod
    public void setup() throws MalformedObjectNameException {
        TestDetector.reset();
        Map<ConfigKey,String> config = new HashMap<ConfigKey,String>();
        config.put(ConfigKey.MBEAN_QUALIFIER,"qualifier=test");
        handler = new MBeanServerHandler(config,getEmptyLogHandler());
        request = new JmxRequestBuilder(RequestType.READ,"java.lang:type=Memory").attribute("HeapMemoryUsage").build();
    }

    @Test
    public void dispatchRequest() throws MalformedObjectNameException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException, IOException {
        JsonRequestHandler reqHandler = createMock(JsonRequestHandler.class);

        Object result = new Object();

        expect(reqHandler.handleAllServersAtOnce(request)).andReturn(false);
        expect(reqHandler.handleRequest(EasyMock.<MBeanServerConnection>anyObject(), eq(request))).andReturn(result);
        replay(reqHandler);
        assertEquals(handler.dispatchRequest(reqHandler, request),result);
    }


    @Test(expectedExceptions = InstanceNotFoundException.class)
    public void dispatchRequestInstanceNotFound() throws MalformedObjectNameException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException, IOException {
        dispatchWithException(new InstanceNotFoundException());
    }


    @Test(expectedExceptions = AttributeNotFoundException.class)
    public void dispatchRequestAttributeNotFound() throws MalformedObjectNameException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException, IOException {
        dispatchWithException(new AttributeNotFoundException());
    }

    @Test(expectedExceptions = IllegalStateException.class)
    public void dispatchRequestIOException() throws MalformedObjectNameException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException, IOException {
        dispatchWithException(new IOException());
    }

    private void dispatchWithException(Exception e) throws InstanceNotFoundException, AttributeNotFoundException, ReflectionException, MBeanException, IOException {
        JsonRequestHandler reqHandler = createMock(JsonRequestHandler.class);

        expect(reqHandler.handleAllServersAtOnce(request)).andReturn(false);
        expect(reqHandler.handleRequest(EasyMock.<MBeanServerConnection>anyObject(), eq(request))).andThrow(e);
        replay(reqHandler);
        handler.dispatchRequest(reqHandler, request);
    }

    @Test
    public void dispatchAtOnce() throws InstanceNotFoundException, IOException, ReflectionException, AttributeNotFoundException, MBeanException {
        JsonRequestHandler reqHandler = createMock(JsonRequestHandler.class);

        Object result = new Object();

        expect(reqHandler.handleAllServersAtOnce(request)).andReturn(true);
        expect(reqHandler.handleRequest(isA(Set.class), eq(request))).andReturn(result);
        replay(reqHandler);
        assertEquals(handler.dispatchRequest(reqHandler, request),result);
    }

    @Test(expectedExceptions = IllegalStateException.class,expectedExceptionsMessageRegExp = ".*Internal.*")
    public void dispatchAtWithException() throws InstanceNotFoundException, IOException, ReflectionException, AttributeNotFoundException, MBeanException {
        JsonRequestHandler reqHandler = createMock(JsonRequestHandler.class);

        Object result = new Object();

        expect(reqHandler.handleAllServersAtOnce(request)).andReturn(true);
        expect(reqHandler.handleRequest(isA(Set.class), eq(request))).andThrow(new IOException());
        replay(reqHandler);
        handler.dispatchRequest(reqHandler, request);
    }


    @Test
    public void mbeanServers() {
        Set<MBeanServer> servers = handler.getMBeanServers();
        assertTrue(servers.size() > 0);
        assertTrue(servers.contains(ManagementFactory.getPlatformMBeanServer()));

        String info = handler.mBeanServersInfo();
        assertTrue(info.contains("Platform MBeanServer"));
        assertTrue(info.contains("type=Memory"));
    }

    @Test
    public void mbeanRegistration() throws JMException {
        try {
            handler.initMBean();
            ObjectName oName = new ObjectName(handler.getObjectName());
            Set<MBeanServer> servers = handler.getMBeanServers();
            boolean found = false;
            for (MBeanServer server : servers) {
                if (server.isRegistered(oName)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found,"MBean not registered");
        } finally {
            handler.unregisterMBeans();
        }
    }

    @Test
    public void mbeanRegistrationWithFailingTestDetector() throws JMException {
        TestDetector.setThrowAddException(true);
        // New setup because detection happens at construction time
        setup();
        try {
            handler.initMBean();
            ObjectName oName = new ObjectName(handler.getObjectName());
            Set<MBeanServer> servers = handler.getMBeanServers();
            boolean found = false;
            for (MBeanServer server : servers) {
                if (server.isRegistered(oName)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found,"MBean not registered");
        } finally {
            TestDetector.setThrowAddException(false);
            handler.unregisterMBeans();
        }
    }

    @Test(expectedExceptions = IllegalStateException.class,expectedExceptionsMessageRegExp = ".*not register.*")
    public void mbeanRegistrationFailed() throws JMException {
        TestDetector.setThrowAddException(true);
        // New setup because detection happens at construction time
        setup();
        try {
            handler.registerMBean(new Dummy(true,"test:type=dummy"));
        } finally {
            TestDetector.setThrowAddException(false);
        }
    }

    @Test(expectedExceptions = InstanceNotFoundException.class)
    public void mbeanUnregistrationFailed1() throws JMException {
        handler.registerMBean(new Dummy(false,"test:type=dummy"));
        ManagementFactory.getPlatformMBeanServer().unregisterMBean(new ObjectName("test:type=dummy"));
        handler.unregisterMBeans();
    }

    @Test(expectedExceptions = JMException.class,expectedExceptionsMessageRegExp = ".*(dummy[12].*){2}.*")
    public void mbeanUnregistrationFailed2() throws JMException {
        handler.registerMBean(new Dummy(false,"test:type=dummy1"));
        handler.registerMBean(new Dummy(false,"test:type=dummy2"));
        ManagementFactory.getPlatformMBeanServer().unregisterMBean(new ObjectName("test:type=dummy1"));
        ManagementFactory.getPlatformMBeanServer().unregisterMBean(new ObjectName("test:type=dummy2"));
        handler.unregisterMBeans();
    }

    @Test
    public void serverHandle() {
        ServerHandle handle = handler.getServerHandle();
        assertNotNull(handle);
    }


    @Test
    public void fallThrough() throws MalformedObjectNameException {
        TestDetector.setFallThrough(true);
        setup();
        try {
            ServerHandle handle = handler.getServerHandle();
            assertNull(handle.getProduct());
        } finally {
            TestDetector.setFallThrough(false);
        }
    }

    // ===================================================================================================


    private LogHandler getEmptyLogHandler() {
        return new LogHandler() {
            public void debug(String message) {
            }

            public void info(String message) {
            }

            public void error(String message, Throwable t) {
            }
        };
    }


    interface DummyMBean {

    }
    private class Dummy implements DummyMBean,MBeanRegistration {

        private boolean throwException;
        private String name;

        public Dummy(boolean b,String pName) {
            throwException = b;
            name = pName;
        }

        public ObjectName preRegister(MBeanServer server, ObjectName pName) throws Exception {
            if (throwException) {
                throw new RuntimeException();
            }
            return new ObjectName(name);
        }

        public void postRegister(Boolean registrationDone) {
        }

        public void preDeregister() throws Exception {
        }

        public void postDeregister() {
        }
    }
}
