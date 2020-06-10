/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.tomcat78x;

import org.apache.catalina.connector.Request;
import org.apache.skywalking.apm.agent.core.conf.Config;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.agent.core.context.trace.TraceSegment;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.EnhancedInstance;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.InstanceMethodsAroundInterceptor;
import org.apache.skywalking.apm.agent.core.plugin.interceptor.enhance.MethodInterceptResult;
import org.apache.skywalking.apm.agent.core.util.CollectionUtil;
import org.apache.skywalking.apm.agent.core.util.MethodUtil;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.util.StringUtil;
import org.apache.tomcat.util.http.Parameters;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link TomcatInvokeInterceptor} fetch the serialized context data by using {@link
 * HttpServletRequest#getHeader(String)}. The {@link TraceSegment#refs} of current trace segment
 * will reference to the trace segment id of the previous level if the serialized context is not
 * null.
 */
public class TomcatInvokeInterceptor implements InstanceMethodsAroundInterceptor {

    private static boolean IS_SERVLET_GET_STATUS_METHOD_EXIST;
    private static final String SERVLET_RESPONSE_CLASS = "javax.servlet.http.HttpServletResponse";
    private static final String GET_STATUS_METHOD = "getStatus";
    private static final ILog logger = LogManager.getLogger(TomcatInvokeInterceptor.class);
    private static final String DEFAULT_VALUE = "unknown";

    static {
        IS_SERVLET_GET_STATUS_METHOD_EXIST = MethodUtil
                .isMethodExist(TomcatInvokeInterceptor.class.getClassLoader(), SERVLET_RESPONSE_CLASS,
                        GET_STATUS_METHOD);
    }

    /**
     * * The {@link TraceSegment#refs} of current trace segment will reference to the trace segment id
     * of the previous level if the serialized context is not null.
     *
     * @param result change this result, if you want to truncate the method.
     */
    @Override
    public void beforeMethod(EnhancedInstance objInst, Method method, Object[] allArguments,
                             Class<?>[] argumentsTypes,
                             MethodInterceptResult result) throws Throwable {
        Request request = (Request) allArguments[0];
        ContextCarrier contextCarrier = new ContextCarrier();

        CarrierItem next = contextCarrier.items();
        while (next.hasNext()) {
            next = next.next();
            next.setHeadValue(request.getHeader(next.getHeadKey()));
        }

        AbstractSpan span = ContextManager.createEntrySpan(request.getRequestURI(), contextCarrier);
        Tags.URL.set(span, request.getRequestURL().toString());
        Tags.HTTP.METHOD.set(span, request.getMethod());
        span.setComponent(ComponentsDefine.TOMCAT);
        String methodType = request.getMethod();
        span.setMethodType(StringUtil.isEmpty(methodType) ? DEFAULT_VALUE : request.getMethod());
        String clientIp = getClientIp(request);
        span.setClientIp(StringUtil.isEmpty(clientIp) ? DEFAULT_VALUE : clientIp);
        SpanLayer.asHttp(span);
        logger.info("http method is:{},clientIp is:{} ", span.getMethodType(), span.getClientIp());

        if (Config.Plugin.Tomcat.COLLECT_HTTP_PARAMS) {
            final Map<String, String[]> parameterMap = new HashMap<>();
            final org.apache.coyote.Request coyoteRequest = request.getCoyoteRequest();
            final Parameters parameters = coyoteRequest.getParameters();
            for (final Enumeration<String> names = parameters.getParameterNames();
                 names.hasMoreElements(); ) {
                final String name = names.nextElement();
                parameterMap.put(name, parameters.getParameterValues(name));
            }

            if (!parameterMap.isEmpty()) {
                String tagValue = CollectionUtil.toString(parameterMap);
                tagValue = Config.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD > 0 ? StringUtil
                        .cut(tagValue, Config.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD) : tagValue;
                Tags.HTTP.PARAMS.set(span, tagValue);
            }
        }
    }

    private String getClientIp(Request request) {
        String ip = request.getHeader("X-Real-IP");
        String unknown = DEFAULT_VALUE;
        if (StringUtil.isEmpty(ip) || unknown.equalsIgnoreCase(ip)) {
            request.getHeader("x-forwarded-for");
        }
        if (StringUtil.isEmpty(ip) || unknown.equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (StringUtil.isEmpty(ip) || unknown.equalsIgnoreCase(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (StringUtil.isEmpty(ip) || unknown.equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (StringUtil.isEmpty(ip) || unknown.equalsIgnoreCase(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (StringUtil.isEmpty(ip) || unknown.equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }

        if (!StringUtil.isEmpty(ip)) {
            return ip.split(",")[0];
        }
        return ip;
    }

    @Override
    public Object afterMethod(EnhancedInstance objInst, Method method, Object[] allArguments,
                              Class<?>[] argumentsTypes,
                              Object ret) throws Throwable {
        HttpServletResponse response = (HttpServletResponse) allArguments[1];
        HttpServletRequest request = (HttpServletRequest) allArguments[0];

        Integer bizCode = (Integer) request.getAttribute("bizCode");

        AbstractSpan span = ContextManager.activeSpan();
        Tags.STATUS_CODE.set(span, Integer.toString(response.getStatus()));
        if (IS_SERVLET_GET_STATUS_METHOD_EXIST && response.getStatus() >= 400) {
            span.errorOccurred();
        } else {
            if (bizCode != null && bizCode != 0) {
                span.errorOccurred();
                Tags.BIZ_CODE.set(span, Integer.toString(bizCode));
            }
        }
        ContextManager.stopSpan();
        ContextManager.getRuntimeContext().remove(Constants.FORWARD_REQUEST_FLAG);
        return ret;
    }

    @Override
    public void handleMethodException(EnhancedInstance objInst, Method method, Object[] allArguments,
                                      Class<?>[] argumentsTypes, Throwable t) {
        AbstractSpan span = ContextManager.activeSpan();
        span.log(t);
        span.errorOccurred();
    }
}
