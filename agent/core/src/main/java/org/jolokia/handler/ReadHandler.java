package org.jolokia.handler;

import org.jolokia.request.*;
import org.jolokia.restrictor.Restrictor;
import org.jolokia.util.RequestType;

import javax.management.*;
import java.io.IOException;
import java.util.*;

/*
 *  Copyright 2009-2010 Roland Huss
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


/**
 * Handler for managing READ requests for reading attributes.
 *
 * @author roland
 * @since Jun 12, 2009
 */
public class ReadHandler extends JsonRequestHandler<JmxReadRequest> {

    /**
     * Read handler constructor
     *
     * @param pRestrictor access restriction to apply
     */
    public ReadHandler(Restrictor pRestrictor) {
        super(pRestrictor);
    }

    /** {@inheritDoc} */
    @Override
    public RequestType getType() {
        return RequestType.READ;
    }

    /**
     * For a simple requests (one MBean, one attribute) we let the dispatching of the servers
     * done by the upper level. If the request is for an MBean pattern or multiple attributes
     * are required, we try multiple requests for multiple server.
     *
     *
     * @param pRequest request to decide on whether to handle all request at once
     * @return true if this is a multi attribute request, has an MBean pattern to look for or is a request for
     *         all attributes.
     */
    @Override
    public boolean handleAllServersAtOnce(JmxReadRequest pRequest) {
        return pRequest.getObjectName().isPattern() || pRequest.isMultiAttributeMode() || !pRequest.hasAttribute();
    }

    /**
     * Used for a request to a single attribute from a single MBean. Merging of MBeanServers is done
     * one layer above.
     *
     *
     * @param pServer server on which to request the attribute
     * @param pRequest the request itself.
     * @return the attribute's value
     */
    @Override
    public Object doHandleRequest(MBeanServerConnection pServer, JmxReadRequest pRequest)
            throws InstanceNotFoundException, AttributeNotFoundException, ReflectionException, MBeanException, IOException {
        checkRestriction(pRequest.getObjectName(), pRequest.getAttributeName());
        return pServer.getAttribute(pRequest.getObjectName(), pRequest.getAttributeName());
    }

    /** {@inheritDoc} */
    @Override
    public Object doHandleRequest(Set<MBeanServerConnection> pServers, JmxReadRequest pRequest)
            throws InstanceNotFoundException, AttributeNotFoundException, ReflectionException, MBeanException, IOException {
        ObjectName oName = pRequest.getObjectName();
        ValueFaultHandler faultHandler = pRequest.getValueFaultHandler();
        if (oName.isPattern()) {
            return fetchAttributesForMBeanPattern(pServers, pRequest);
        } else {
            return fetchAttributes(pServers,oName,pRequest.getAttributeNames(),faultHandler);
        }
    }

    private Object fetchAttributesForMBeanPattern(Set<MBeanServerConnection> pServers, JmxReadRequest pRequest)
            throws IOException, InstanceNotFoundException, ReflectionException, AttributeNotFoundException, MBeanException {
        ObjectName objectName = pRequest.getObjectName();
        ValueFaultHandler faultHandler = pRequest.getValueFaultHandler();
        Set<ObjectName> names = searchMBeans(pServers, objectName);
        Map<String,Object> ret = new HashMap<String, Object>();
        List<String> attributeNames = pRequest.getAttributeNames();
        for (ObjectName name : names) {
            if (!pRequest.hasAttribute()) {
                Map values = (Map) fetchAttributes(pServers,name, null, faultHandler);
                if (values != null && values.size() > 0) {
                    ret.put(name.getCanonicalName(),values);
                }
            } else {
                List<String> filteredAttributeNames = filterAttributeNames(pServers,name,attributeNames);
                if (filteredAttributeNames.size() == 0) {
                    continue;
                }
                ret.put(name.getCanonicalName(),
                        fetchAttributes(pServers,name,filteredAttributeNames, faultHandler));
            }
        }
        if (ret.size() == 0) {
            throw new IllegalArgumentException("No matching attributes " +
                    pRequest.getAttributeNames() + " found on MBeans " + names);
        }
        return ret;
    }

    private Set<ObjectName> searchMBeans(Set<MBeanServerConnection> pServers, ObjectName pObjectName) throws IOException, InstanceNotFoundException {
        Set<ObjectName> names = new HashSet<ObjectName>();
        for (MBeanServerConnection server : pServers) {
            Set<ObjectName> found = server.queryNames(pObjectName,null);
            if (found != null) {
                names.addAll(found);
            }
        }
        if (names == null || names.size() == 0) {
            throw new InstanceNotFoundException("No MBean with pattern " + pObjectName +
                    " found for reading attributes");
        }
        return names;
    }

    // Return only those attributes of an mbean which has one of the given names
    private List<String> filterAttributeNames(Set<MBeanServerConnection> pServers,ObjectName pName, List<String> pNames)
            throws InstanceNotFoundException, IOException, ReflectionException {
        Set<String> attrs = new HashSet<String>(getAllAttributesNames(pServers,pName));
        List<String> ret = new ArrayList<String>();
        for (String name : pNames) {
            if (attrs.contains(name)) {
                ret.add(name);
            }
        }
        return ret;
    }

    private Object fetchAttributes(Set<MBeanServerConnection> pServers, ObjectName pMBeanName, List<String> pAttributeNames,
                                   ValueFaultHandler pFaultHandler)
            throws InstanceNotFoundException, IOException, ReflectionException, AttributeNotFoundException, MBeanException {
        List<String> attributes = pAttributeNames;
        if (shouldAllAttributesBeFetched(pAttributeNames)) {
            // All attributes are requested, we look them up now
            attributes = getAllAttributesNames(pServers,pMBeanName);
        }
        Map<String,Object> ret = new HashMap<String, Object>();

        for (String attribute : attributes) {
            try {
                checkRestriction(pMBeanName, attribute);
                ret.put(attribute,getAttribute(pServers, pMBeanName, attribute));
            } catch (MBeanException e) {
                // The fault handler might to decide to rethrow the
                // exception in which case nothing is put extra into ret.
                // Otherwise, the replacement value as returned by the
                // fault handler is inserted.
                ret.put(attribute, pFaultHandler.handleException(e));
            } catch (AttributeNotFoundException e) {
                ret.put(attribute, pFaultHandler.handleException(e));
            } catch (InstanceNotFoundException e) {
                ret.put(attribute, pFaultHandler.handleException(e));
            } catch (ReflectionException e) {
                ret.put(attribute, pFaultHandler.handleException(e));
            } catch (IOException e) {
                ret.put(attribute, pFaultHandler.handleException(e));
            } catch (RuntimeException e) {
                ret.put(attribute, pFaultHandler.handleException(e));
            }
        }
        return ret;
    }

    private boolean shouldAllAttributesBeFetched(List<String> pAttributeNames) {
       if (pAttributeNames == null || pAttributeNames.size() == 0) {
           return true;
       } else {
           return pAttributeNames.size() == 1 && pAttributeNames.get(0) == null;
       }
    }

    // Get the MBeanInfo from one of the provided MBeanServers
    private MBeanInfo getMBeanInfo(Set<MBeanServerConnection> pServers, ObjectName pObjectName) throws
            IntrospectionException, InstanceNotFoundException, IOException, ReflectionException {
        for (MBeanServerConnection server : pServers) {
            try {
                return server.getMBeanInfo(pObjectName);
            } catch (InstanceNotFoundException exp) {
                // Ok, we try the next server ....
            }
        }
        // When we reach this, no MBeanServer know about the requested MBean.
        // Hence, we throw our own InstanceNotFoundException here
        throw new InstanceNotFoundException("No MBean with ObjectName " + pObjectName + " found in any MBeanServer");
    }

    // Try multiple servers for fetching an attribute
    private Object getAttribute(Set<MBeanServerConnection> pServers, ObjectName pMBeanName, String attribute)
            throws MBeanException, AttributeNotFoundException, ReflectionException, IOException, InstanceNotFoundException {
        for (MBeanServerConnection server : pServers) {
            try {
                return server.getAttribute(pMBeanName, attribute);
            } catch (InstanceNotFoundException exp) {
                // Happens on servers which dont know about this attribute.
                // We continue, but when we reach the end of the loop,
                // we throw an InstanceNotFoundException on our own
            }
        }
        throw new InstanceNotFoundException("No MBean with ObjectName " + pMBeanName + " found at any known MBeanServer");
    }

    // Return a set of attributes as a map with the attribute name as key and their values as values
    private List<String> getAllAttributesNames(Set<MBeanServerConnection> pServers, ObjectName pObjectName)
            throws InstanceNotFoundException, IOException, ReflectionException {
        try {
            MBeanInfo mBeanInfo = getMBeanInfo(pServers, pObjectName);
            List<String> ret = new ArrayList<String>();
            for (MBeanAttributeInfo attrInfo : mBeanInfo.getAttributes()) {
                if (attrInfo.isReadable()) {
                    ret.add(attrInfo.getName());
                }
            }
            return ret;
        } catch (IntrospectionException e) {
            throw new IllegalStateException("Internal error while retrieving list: " + e,e);
        }
    }

    private void checkRestriction(ObjectName mBeanName, String attribute) {
        if (!getRestrictor().isAttributeReadAllowed(mBeanName,attribute)) {
            throw new SecurityException("Reading attribute " + attribute +
                    " is forbidden for MBean " + mBeanName.getCanonicalName());
        }
    }

    /**
     * We override it here with a noop since we do a more fine grained
     * check during processing of the request.
     */
    @Override
    protected void checkForRestriction(JmxReadRequest pRequest) {

    }
}
