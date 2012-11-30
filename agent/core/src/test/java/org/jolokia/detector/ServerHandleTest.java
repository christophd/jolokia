package org.jolokia.detector;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.management.*;

import org.easymock.EasyMock;
import static org.easymock.EasyMock.*;

import org.jolokia.mbean.Config;
import org.jolokia.mbean.ConfigMBean;
import org.jolokia.util.ConfigKey;
import org.jolokia.util.LogHandler;
import org.json.simple.JSONObject;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * @author roland
 * @since 06.06.12
 */
public class ServerHandleTest {


    private ServerHandle serverHandle;
    private String vendor;
    private String product;
    private String version;
    private URL url;
    private Map<String,String> extraInfo;

    @BeforeMethod
    public void setup() throws MalformedURLException {
        extraInfo = new HashMap<String, String>();
        extraInfo.put("extra1", "value1");
        vendor = "acim";
        product = "dukeNukem";
        version = "forEver";
        url = new URL("http://acim.org");
        serverHandle = new ServerHandle(vendor, product, version, url, extraInfo);
    }

    @Test
    public void basics() throws MalformedURLException {
        assertEquals(serverHandle.getAgentUrl(), url);
        assertEquals(serverHandle.getProduct(),product);
        assertEquals(serverHandle.getVendor(),vendor);
        assertEquals(serverHandle.getExtraInfo(null).get("extra1"),"value1");
        assertEquals(serverHandle.getVersion(),version);
    }

    @Test
    public void toJson() {
        JSONObject json = serverHandle.toJSONObject(null);
        assertEquals(json.get("vendor"),vendor);
        assertEquals(json.get("product"),product);
        assertEquals(json.get("version"),version);
        assertEquals(json.get("agent-url"),url.toExternalForm());
        assertEquals(((JSONObject) json.get("extraInfo")).get("extra1"),"value1");
    }

    @Test
    public void allNull() {
        ServerHandle handle = new ServerHandle(null,null,null,null,null);
        assertNull(handle.getVendor());
        assertNull(handle.toJSONObject(null).get("extraInfo"));
    }

    @Test
    public void detectorOptions() {
        Map<ConfigKey,String> opts = new HashMap<ConfigKey, String>();
        opts.put(ConfigKey.DETECTOR_OPTIONS,"{\"dukeNukem\" : {\"doIt\" : true }}");
        JSONObject config = serverHandle.getDetectorOptions(opts,null);
        assertTrue((Boolean) config.get("doIt"));
    }

    @Test
    public void detectorOptionsEmpty() {
        JSONObject config = serverHandle.getDetectorOptions(new HashMap<ConfigKey, String>(),null);
        assertNull(config);
    }

    @Test
    public void detectOptionsFail() {
        LogHandler handler = EasyMock.createMock(LogHandler.class);
        handler.error(matches("^.*parse options.*"),isA(Exception.class));
        replay(handler);

        Map<ConfigKey,String> opts = new HashMap<ConfigKey, String>();
        opts.put(ConfigKey.DETECTOR_OPTIONS,"blub: bla");
        JSONObject config = serverHandle.getDetectorOptions(opts,handler);
        assertNull(config);
        verify(handler);
    }

    @Test
    public void registerAtMBeanServer() throws MalformedObjectNameException, MBeanRegistrationException, InstanceAlreadyExistsException, NotCompliantMBeanException {
        Config config = new Config(null,null,null);
        ObjectName oName = new ObjectName("jolokia:type=Config");
        ObjectInstance oInstance = new ObjectInstance(oName,Config.class.getName());
        MBeanServer server = EasyMock.createMock(MBeanServer.class);
        expect(server.registerMBean(eq(config),eq(oName))).andReturn(oInstance);
        replay(server);

        ObjectName resName = serverHandle.registerMBeanAtServer(server,config,"jolokia:type=Config");
        assertEquals(resName,oName);

        verify(server);
    }

    @Test
    public void registerAtMBeanServer2() throws MalformedObjectNameException, MBeanRegistrationException, InstanceAlreadyExistsException, NotCompliantMBeanException {
        Config config = new Config(null,null,null);
        ObjectInstance oInstance = new ObjectInstance("jolokia:type=dummy",Config.class.getName());
        MBeanServer server = EasyMock.createMock(MBeanServer.class);
        expect(server.registerMBean(config,null)).andReturn(oInstance);
        replay(server);

        ObjectName resName = serverHandle.registerMBeanAtServer(server,config,null);
        assertEquals(resName,new ObjectName("jolokia:type=dummy"));

        verify(server);
    }
}
