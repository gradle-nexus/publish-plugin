/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.marcphilipp.gradle.nexus;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

public class WireMockExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

    public static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(WireMockExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        WireMockServer server = getStore(context).getOrComputeIfAbsent("server", key -> new WireMockServer(), WireMockServer.class);
        server.start();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        getServer(context).stop();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType().equals(WireMockServer.class);
    }

    @Override
    public WireMockServer resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return getServer(extensionContext);
    }

    private WireMockServer getServer(ExtensionContext context) {
        return getStore(context).get("server", WireMockServer.class);
    }

    private ExtensionContext.Store getStore(ExtensionContext extensionContext) {
        return extensionContext.getStore(NAMESPACE);
    }
}
