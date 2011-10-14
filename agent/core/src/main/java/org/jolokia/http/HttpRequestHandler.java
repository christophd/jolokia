package org.jolokia.http;

import java.io.*;
import java.util.List;
import java.util.Map;

import javax.management.*;

import org.jolokia.backend.BackendManager;
import org.jolokia.request.JmxRequest;
import org.jolokia.request.JmxRequestFactory;
import org.jolokia.util.LogHandler;
import org.json.simple.*;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

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
 * Request handler with no dependency on the servlet API so that it can be used in
 * several different environments (like for the Sun JDK 6 {@link com.sun.net.httpserver.HttpServer}.
 *
 * @author roland
 * @since Mar 3, 2010
 */
public class HttpRequestHandler {

    // handler for contacting the MBean server(s)
    private BackendManager backendManager;

    // Logging abstraction
    private LogHandler logHandler;

    /**
     * Request handler for parsing HTTP request and dispatching to the appropriate
     * request handler (with help of the backend manager)
     *
     * @param pBackendManager backend manager to user
     * @param pLogHandler log handler to where to put out logging
     */
    public HttpRequestHandler(BackendManager pBackendManager, LogHandler pLogHandler) {
        backendManager = pBackendManager;
        logHandler = pLogHandler;
    }

    /**
     * Handle a GET request
     *
     * @param pUri URI leading to this request
     * @param pPathInfo path of the request
     * @param pParameterMap parameters of the GET request  @return the response
     */
    public JSONAware handleGetRequest(String pUri, String pPathInfo, Map<String, String[]> pParameterMap) {
        JmxRequest jmxReq =
                JmxRequestFactory.createGetRequest(pPathInfo,pParameterMap);

        if (backendManager.isDebug()) {
            logHandler.debug("URI: " + pUri);
            logHandler.debug("Path-Info: " + pPathInfo);
            logHandler.debug("Request: " + jmxReq.toString());
        }

        return executeRequest(jmxReq);
    }

    /**
     * Handle the input stream as given by a POST request
     *
     *
     * @param pUri URI leading to this request
     * @param pInputStream input stream of the post request
     * @param pEncoding optional encoding for the stream. If null, the default encoding is used
     * @param pParameterMap additional processing parameters
     * @return the JSON object containing the json results for one or more {@link JmxRequest} contained
     *         within the answer.
     *
     * @throws IOException if reading from the input stream fails
     */
    public JSONAware handlePostRequest(String pUri, InputStream pInputStream, String pEncoding, Map<String, String[]>  pParameterMap)
            throws IOException {
        if (backendManager.isDebug()) {
            logHandler.debug("URI: " + pUri);
        }

        Object jsonRequest = extractJsonRequest(pInputStream,pEncoding);
        if (jsonRequest instanceof JSONArray) {
            List<JmxRequest> jmxRequests = JmxRequestFactory.createPostRequests((List) jsonRequest,pParameterMap);

            JSONArray responseList = new JSONArray();
            for (JmxRequest jmxReq : jmxRequests) {
                if (backendManager.isDebug()) {
                    logHandler.debug("Request: " + jmxReq.toString());
                }
                // Call handler and retrieve return value
                JSONObject resp = executeRequest(jmxReq);
                responseList.add(resp);
            }
            return responseList;
        } else if (jsonRequest instanceof JSONObject) {
            JmxRequest jmxReq = JmxRequestFactory.createPostRequest((Map<String, ?>) jsonRequest,pParameterMap);
            return executeRequest(jmxReq);
        } else {
            throw new IllegalArgumentException("Invalid JSON Request " + jsonRequest);
        }
    }

    private Object extractJsonRequest(InputStream pInputStream, String pEncoding) throws IOException {
        InputStreamReader reader = null;
        try {
            reader =
                    pEncoding != null ?
                            new InputStreamReader(pInputStream, pEncoding) :
                            new InputStreamReader(pInputStream);
            JSONParser parser = new JSONParser();
            return parser.parse(reader);
        } catch (ParseException exp) {
            throw new IllegalArgumentException("Invalid JSON request " + reader,exp);
        }
    }

    /**
     * Execute a single {@link JmxRequest}. If a checked  exception occurs,
     * this gets translated into the appropriate JSON object which will get returned.
     * Note, that these exceptions gets *not* translated into an HTTP error, since they are
     * supposed <em>Jolokia</em> specific errors above the transport layer.
     *
     * @param pJmxReq the request to execute
     * @return the JSON representation of the answer.
     */
    private JSONObject executeRequest(JmxRequest pJmxReq) {
        // Call handler and retrieve return value
        try {
            return backendManager.handleRequest(pJmxReq);
        } catch (ReflectionException e) {
            return getErrorJSON(404,e);
        } catch (InstanceNotFoundException e) {
            return getErrorJSON(404,e);
        } catch (MBeanException e) {
            return getErrorJSON(500,e.getTargetException());
        } catch (AttributeNotFoundException e) {
            return getErrorJSON(404,e);
        } catch (UnsupportedOperationException e) {
            return getErrorJSON(500,e);
        } catch (IOException e) {
            return getErrorJSON(500,e);
        } catch (IllegalArgumentException e) {
            return getErrorJSON(400,e);
        } catch (SecurityException e) {
            // Wipe out stacktrace
            return getErrorJSON(403,new Exception(e.getMessage()));
        } catch (RuntimeMBeanException e) {
            // Use wrapped exception
            return errorForUnwrappedException(e);
        }
    }


    /**
     * Utility method for handling single runtime exceptions and errors. This method is called
     * in addition to and after {@link #executeRequest(JmxRequest)} to catch additional errors.
     * They are two different methods because of bulk requests, where each individual request can
     * lead to an error. So, each individual request is wrapped with the error handling of
     * {@link #executeRequest(JmxRequest)}
     * whereas the overall handling is wrapped with this method. It is hence more coarse grained,
     * leading typically to an status code of 500.
     *
     * Summary: This method should be used as last security belt is some exception should escape
     * from a single request processing in {@link #executeRequest(JmxRequest)}.
     *
     * @param pThrowable exception to handle
     * @return its JSON representation
     */
    public JSONObject handleThrowable(Throwable pThrowable) {
        if (pThrowable instanceof IllegalArgumentException) {
            return getErrorJSON(400,pThrowable);
        } else if (pThrowable instanceof SecurityException) {
            // Wipe out stacktrace
            return getErrorJSON(403,new Exception(pThrowable.getMessage()));
        } else {
            return getErrorJSON(500,pThrowable);
        }
    }


    /**
     * Get the JSON representation for a an exception
     *
     * @param pErrorCode the HTTP error code to return
     * @param pExp the exception or error occured
     * @return the json representation
     */
    public JSONObject getErrorJSON(int pErrorCode, Throwable pExp) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("status",pErrorCode);
        jsonObject.put("error",getExceptionMessage(pExp));
        jsonObject.put("error_type",pExp.getClass().getName());
        StringWriter writer = new StringWriter();
        pExp.printStackTrace(new PrintWriter(writer));
        jsonObject.put("stacktrace",writer.toString());
        if (backendManager.isDebug()) {
            backendManager.error("Error " + pErrorCode,pExp);
        }
        return jsonObject;
    }


    /**
     * Check whether the given host and/or address is allowed to access this agent.
     *
     * @param pHost host to check
     * @param pAddress address to check
     */
    public void checkClientIPAccess(String pHost, String pAddress) {
        if (!backendManager.isRemoteAccessAllowed(pHost,pAddress)) {
            throw new SecurityException("No access from client " + pAddress + " allowed");
        }
    }

    // Extract class and exception message for an error message
    private String getExceptionMessage(Throwable pException) {
        String message = pException.getLocalizedMessage();
        return pException.getClass().getName() + (message != null ? " : " + message : "");
    }

    // Unwrap an exception to get to the 'real' exception
    // and extract the error code accordingly
    private JSONObject errorForUnwrappedException(Exception e) {
        Throwable cause = e.getCause();
        int code = cause instanceof IllegalArgumentException ? 400 : cause instanceof SecurityException ? 403 : 500;
        return getErrorJSON(code,cause);
    }

}
