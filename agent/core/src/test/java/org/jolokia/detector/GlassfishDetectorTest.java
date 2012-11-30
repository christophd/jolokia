package org.jolokia.detector;

import java.io.IOException;
import java.util.*;

import javax.management.*;

import org.jolokia.util.ConfigKey;
import org.jolokia.util.LogHandler;
import org.testng.annotations.Test;

import static org.easymock.EasyMock.*;
import static org.testng.Assert.*;
import static org.testng.AssertJUnit.assertEquals;

/**
 * @author roland
 * @since 06.06.12
 */
public class GlassfishDetectorTest {

    ServerDetector detector = new GlassfishDetector();

    @Test
    public void noDetect() throws MalformedObjectNameException {
        detectDeep(null,null);
    }

    @Test
    public void detectFromSystemProperty() throws MalformedObjectNameException {
        detectDeep(" GlassFish v2 ","2");
    }

    @Test
    public void detectWrongVersion() throws MalformedObjectNameException {
        detectDeep(" Blub ",null);
    }

    @Test
    public void detectFromSystemPropertyWithOracle() throws MalformedObjectNameException {
        detectDeep("Oracle Glassfish v3.1.2","3.1.2");
    }

    private void detectDeep(String property,String version) throws MalformedObjectNameException {
        MBeanServer mockServer = createMock(MBeanServer.class);
        expect(mockServer.queryNames(new ObjectName("com.sun.appserv:j2eeType=J2EEServer,*"), null)).
                andReturn(null).anyTimes();
        expect(mockServer.queryNames(new ObjectName("amx:type=domain-root,*"),null)).
                andReturn(null).anyTimes();
        replay(mockServer);
        if (property != null) {
            System.setProperty("glassfish.version",property);
            if (version == null) {
                assertNull(detector.detect(new HashSet<MBeanServer>(Arrays.asList(mockServer))));
            } else {
                assertEquals(detector.detect(new HashSet<MBeanServer>(Arrays.asList(mockServer))).getVersion(),version);
            }
            System.clearProperty("glassfish.version");
        } else {
            assertNull(detector.detect(new HashSet<MBeanServer>(Arrays.asList(mockServer))));
        }
        verify(mockServer);
    }

    @Test
    public void detectFallback() throws InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException, MalformedObjectNameException {
        ObjectName serverMbean = new ObjectName(SERVER_MBEAN);
        MBeanServer mockServer = createMock(MBeanServer.class);

        expect(mockServer.queryNames(new ObjectName("com.sun.appserv:j2eeType=J2EEServer,*"),null)).
                andReturn(new HashSet<ObjectName>(Arrays.asList(serverMbean))).anyTimes();
        expect(mockServer.getAttribute(serverMbean, "serverVersion")).andReturn("GlassFish 3x");
        expect(mockServer.queryNames(new ObjectName("com.sun.appserver:type=Host,*"),null)).
                andReturn(new HashSet<ObjectName>(Arrays.asList(serverMbean))).anyTimes();
        replay(mockServer);

        ServerHandle info = detector.detect(new HashSet<MBeanServer>(Arrays.asList(mockServer)));
        assertEquals(info.getVersion(), "3");
        assertEquals(info.getProduct(),"glassfish");
    }



    @Test
    public void detect() throws MalformedObjectNameException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException {
        doPlainDetect();
    }

    private ServerHandle doPlainDetect() throws MalformedObjectNameException, MBeanException, AttributeNotFoundException, InstanceNotFoundException, ReflectionException {
        ObjectName serverMbean = new ObjectName(SERVER_MBEAN);
        MBeanServer mockServer = createMock(MBeanServer.class);

        expect(mockServer.queryNames(new ObjectName("com.sun.appserv:j2eeType=J2EEServer,*"),null)).
                andReturn(new HashSet<ObjectName>(Arrays.asList(serverMbean))).anyTimes();
        expect(mockServer.getAttribute(serverMbean, "serverVersion")).andReturn("GlassFish v3");
        expect(mockServer.queryNames(new ObjectName("amx:type=domain-root,*"),null)).
                andReturn(new HashSet<ObjectName>(Arrays.asList(serverMbean))).anyTimes();
        expect(mockServer.getAttribute(serverMbean,"ApplicationServerFullVersion")).andReturn(" GlassFish v3.1 ");
        replay(mockServer);

        HashSet<MBeanServer> mbeanServers = new HashSet<MBeanServer>(Arrays.asList(mockServer));
        ServerHandle info = detector.detect(mbeanServers);
        assertEquals(info.getVersion(), "3.1");
        assertEquals(info.getProduct(),"glassfish");
        Map<String,String> extra =
                info.getExtraInfo(mbeanServers);
        assertEquals(extra.get("amxBooted"), "true");
        return info;
    }


    @Test
    public void postDetectWithPositiveConfig() throws MalformedObjectNameException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException {
        postDetectPositive("{\"glassfish\": {\"bootAmx\" : true}}");
    }

    @Test
    public void postDetectWithNullConfig() throws MalformedObjectNameException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException {
        postDetectPositive(null);
    }

    @Test
    public void postDetectWithNegativConfig() throws MalformedObjectNameException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException {
        ServerHandle handle = doPlainDetect();
        MBeanServer mockServer = createMock(MBeanServer.class);
        expect(mockServer.queryNames(new ObjectName("amx:type=domain-root,*"),null)).andReturn(null).anyTimes();
        replay(mockServer);
        Map<ConfigKey,String> config = new HashMap<ConfigKey, String>();
        config.put(ConfigKey.DETECTOR_OPTIONS,"{\"glassfish\": {\"bootAmx\" : false}}");
        handle.postDetect(new HashSet<MBeanServer>(Arrays.asList(mockServer)), config, null);
        verify(mockServer);
    }

    private void postDetectPositive(String opts) throws MalformedObjectNameException, MBeanException, AttributeNotFoundException, InstanceNotFoundException, ReflectionException {
        ServerHandle handle = doPlainDetect();
        MBeanServer mockServer = createMock(MBeanServer.class);
        expect(mockServer.queryNames(new ObjectName("amx:type=domain-root,*"),null)).andReturn(null).anyTimes();
        expect(mockServer.invoke(new ObjectName("amx-support:type=boot-amx"),"bootAMX",null,null)).andReturn(null);
        replay(mockServer);
        Map<ConfigKey,String> config = new HashMap<ConfigKey, String>();
        config.put(ConfigKey.DETECTOR_OPTIONS,opts);
        HashSet<MBeanServer> servers = new HashSet<MBeanServer>(Arrays.asList(mockServer));
        handle.postDetect(servers, config, null);
        handle.preDispatch(servers,null);
        verify(mockServer);
    }

    @Test
    public void detectInstanceNotFoundException() throws MalformedObjectNameException, MBeanException, AttributeNotFoundException, InstanceNotFoundException, ReflectionException {
        detectExceptionDuringPostProcess("^.*No bootAmx.*$",new InstanceNotFoundException("Negative"));
    }

    @Test
    public void detectOtherException() throws MalformedObjectNameException, MBeanException, AttributeNotFoundException, InstanceNotFoundException, ReflectionException {
        detectExceptionDuringPostProcess("^.*bootAmx.*$",new MBeanException(new Exception("Negative")));
    }

    private void detectExceptionDuringPostProcess(String regexp,Exception exp) throws MalformedObjectNameException, MBeanException, AttributeNotFoundException, InstanceNotFoundException, ReflectionException {
        ServerHandle handle = doPlainDetect();
        MBeanServer mockServer = createMock(MBeanServer.class);
        expect(mockServer.queryNames(new ObjectName("amx:type=domain-root,*"),null)).andReturn(null).anyTimes();
        expect(mockServer.invoke(new ObjectName("amx-support:type=boot-amx"), "bootAMX", null, null)).andThrow(exp);
        LogHandler log = createMock(LogHandler.class);
        log.error(matches(regexp),isA(exp.getClass()));
        replay(mockServer,log);
        Map<ConfigKey,String> config = new HashMap<ConfigKey, String>();
        HashSet<MBeanServer> servers = new HashSet<MBeanServer>(Arrays.asList(mockServer));
        handle.postDetect(servers,config ,log);
        handle.preDispatch(servers,null);
        verify(mockServer);
    }


    private static String SERVER_MBEAN = "com.sun.appserv:j2eeType=J2EEServer,type=bla";

}
