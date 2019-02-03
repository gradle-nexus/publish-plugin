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

package de.marcphilipp.gradle.nexus

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

class WireMockExtension : BeforeEachCallback, AfterEachCallback, ParameterResolver {

    companion object {
        private val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create(WireMockExtension::class.java)
    }

    override fun beforeEach(context: ExtensionContext) {
        val server = getStore(context).getOrComputeIfAbsent("server", { WireMockServer(wireMockConfig().dynamicPort()) }, WireMockServer::class.java)
        server.start()
    }

    override fun afterEach(context: ExtensionContext) {
        getServer(context).stop()
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean {
        return parameterContext.parameter.type == WireMockServer::class.java
    }

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): WireMockServer {
        return getServer(extensionContext)
    }

    private fun getServer(context: ExtensionContext): WireMockServer {
        return getStore(context).get("server", WireMockServer::class.java)
    }

    private fun getStore(extensionContext: ExtensionContext): ExtensionContext.Store {
        return extensionContext.getStore(NAMESPACE)
    }
}
