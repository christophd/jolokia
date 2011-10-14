/*
 * Copyright 2009-2011 Roland Huss
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jolokia.client.request;

import java.util.Arrays;
import java.util.Map;

import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.jolokia.client.exception.J4pException;
import org.testng.annotations.Test;

import static org.testng.AssertJUnit.*;

/**
 * Integration test for listing MBeans
 *
 * @author roland
 */
public class J4pListIntegrationTest extends AbstractJ4pIntegrationTest {

    @Test
    public void simple() throws J4pException {
        J4pListRequest req = new J4pListRequest();
        J4pListResponse resp = j4pClient.execute(req);
        assertNotNull(resp);
    }

    @Test
    public void mbeanMeta() throws J4pException, MalformedObjectNameException {
        J4pListRequest req = new J4pListRequest(new ObjectName("java.lang:type=Memory"));
        J4pListResponse resp = j4pClient.execute(req);
        Map val = resp.getValue();
        assertTrue(val.containsKey("desc"));
        assertTrue(val.containsKey("op"));
        assertTrue(val.containsKey("attr"));
    }

    @Test
    public void withSpace() throws MalformedObjectNameException, J4pException {
        J4pListRequest req = new J4pListRequest("jolokia.it/name=name with space,type=naming");
        J4pListResponse resp = j4pClient.execute(req);
        Map val = resp.getValue();
        assertEquals( ((Map) ((Map) val.get("attr")).get("Ok")).get("type"),"java.lang.String");
    }

    @Test
    public void withSlash() throws MalformedObjectNameException, J4pException {
        J4pListRequest reqs[] =  new J4pListRequest[] {
                new J4pListRequest(new ObjectName("jolokia.it:type=naming,name=n!a!m!e with !/!")),
                new J4pListRequest(Arrays.asList("jolokia.it","type=naming,name=n!a!m!e with !/!")),
                new J4pListRequest("jolokia.it/type=naming,name=n!!a!!m!!e with !!!/!!")
        };
        for (J4pListRequest req : reqs) {
            J4pListResponse resp = j4pClient.execute(req);
            Map val = resp.getValue();
            assertEquals( ((Map) ((Map) val.get("attr")).get("Ok")).get("type"),"java.lang.String");
        }
    }
}
