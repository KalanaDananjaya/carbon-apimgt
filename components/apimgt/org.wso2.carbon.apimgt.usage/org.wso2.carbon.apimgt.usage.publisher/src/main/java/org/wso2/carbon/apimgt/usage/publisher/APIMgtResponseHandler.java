/*
*  Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.apimgt.usage.publisher;

import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.apimgt.usage.publisher.dto.ResponsePublisherDTO;
import org.wso2.carbon.apimgt.usage.publisher.internal.UsageComponent;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.util.Map;

/*
* This mediator is to publish events upon success API invocations
*/

public class APIMgtResponseHandler extends AbstractMediator {

    private boolean enabled = UsageComponent.getApiMgtConfigReaderService().isEnabled();

    private volatile APIMgtUsageDataPublisher publisher;

    public APIMgtResponseHandler() {

        if (!enabled) {
            return;
        }

        if (publisher == null) {
            synchronized (this) {
                if (publisher == null) {
                    String publisherClass = UsageComponent.getApiMgtConfigReaderService().
                            getPublisherClass();
                    try {
                        log.debug("Instantiating Data Publisher");
                        publisher = (APIMgtUsageDataPublisher) Class.forName(publisherClass).
                                newInstance();
                        publisher.init();
                    } catch (ClassNotFoundException e) {
                        log.error("Class not found " + publisherClass);
                    } catch (InstantiationException e) {
                        log.error("Error instantiating " + publisherClass);
                    } catch (IllegalAccessException e) {
                        log.error("Illegal access to " + publisherClass);
                    }
                }
            }
        }
    }

    public boolean mediate(MessageContext mc) {

        try {
            if (!enabled) {
                return true;
            }
            long responseSize = 0;
            long responseTime = 0;
            long serviceTime = 0;
            long backendTime = 0;
            long endTime = System.currentTimeMillis();
            long startTime = Long.parseLong((String) (mc.getProperty(
                    APIMgtUsagePublisherConstants.REQUEST_START_TIME)));
            long backendStartTime = Long.parseLong((String) (mc.getProperty(
                    APIMgtUsagePublisherConstants.BACKEND_REQUEST_START_TIME)));
            long backendEndTime = Long.parseLong((String) (mc.getProperty(
                    APIMgtUsagePublisherConstants.BACKEND_REQUEST_END_TIME)));
            boolean cacheHit = false;
            org.apache.axis2.context.MessageContext axis2MC = ((Axis2MessageContext) mc).
                    getAxis2MessageContext();
            Map headers = (Map) axis2MC.getProperty(
                    org.apache.axis2.context.MessageContext.TRANSPORT_HEADERS);
            String contentLength = (String) headers.get("Content-Length");
            if (contentLength != null) {
                responseSize = Integer.parseInt(contentLength);
            } else {  //When chunking is enabled
                try {
                    RelayUtils.buildMessage(axis2MC);
                } catch (IOException ex) {
                    //In case of an exception, it won't be propagated up, instead, will be logged;
                    log.error("Error occurred while building the message to" +
                              " calculate the response body size", ex);
                } catch (XMLStreamException ex) {
                    log.error("Error occurred while building the message to calculate the response" +
                              " body size", ex);
                }
                byte[] size = mc.getEnvelope().getBody().toString().getBytes();
                responseSize = size.length;
            }
            //When start time not properly set
            if (startTime == 0) {
                responseTime = 0;
                backendTime = 0;
                serviceTime = 0;
            } else if (endTime != 0 && backendStartTime != 0 && backendEndTime != 0) { //When
                // response caching is disabled
                responseTime = endTime - startTime;
                backendTime = backendEndTime - backendStartTime;
                serviceTime = responseTime - backendTime;

            } else if (endTime != 0 && backendStartTime == 0) {//When response caching enabled
                responseTime = endTime - startTime;
                serviceTime = responseTime;
                backendTime = 0;
                cacheHit = true;
            }
            ResponsePublisherDTO responsePublisherDTO = new ResponsePublisherDTO();
            responsePublisherDTO.setConsumerKey((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.CONSUMER_KEY));
            responsePublisherDTO.setUsername((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.USER_ID));
            responsePublisherDTO.setTenantDomain(MultitenantUtils.getTenantDomain(
                    responsePublisherDTO.getUsername()));
            responsePublisherDTO.setContext((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.CONTEXT));
            responsePublisherDTO.setApi_version((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.API_VERSION));
            responsePublisherDTO.setApi((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.API));
            responsePublisherDTO.setVersion((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.VERSION));
            responsePublisherDTO.setResourcePath((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.RESOURCE));
            responsePublisherDTO.setMethod((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.HTTP_METHOD));
            responsePublisherDTO.setResponseTime(responseTime);
            responsePublisherDTO.setServiceTime(serviceTime);
            responsePublisherDTO.setBackendTime(backendTime);
            responsePublisherDTO.setHostName((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.HOST_NAME));
            responsePublisherDTO.setApiPublisher((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.API_PUBLISHER));
            responsePublisherDTO.setApplicationName((String) mc.getProperty
                    (APIMgtUsagePublisherConstants.APPLICATION_NAME));
            responsePublisherDTO.setApplicationId((String) mc.getProperty(
                    APIMgtUsagePublisherConstants.APPLICATION_ID));
            responsePublisherDTO.setCacheHit(cacheHit);
            responsePublisherDTO.setResponseSize(responseSize);
            responsePublisherDTO.setEventTime(endTime);//This is the timestamp response event published
            publisher.publishEvent(responsePublisherDTO);

        } catch (Throwable e) {
            log.error("Cannot publish response event. " + e.getMessage(), e);
        }
        return true; // Should never stop the message flow
    }

    public boolean isContentAware() {
        return false;
    }
}

