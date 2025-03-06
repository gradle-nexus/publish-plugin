/*
 * Copyright 2021 the original author or authors.
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

package io.github.gradlenexus.publishplugin

import com.github.tomakehurst.wiremock.WireMockServer
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import ru.lanwen.wiremock.ext.WiremockResolver
import java.lang.IllegalArgumentException

/**
 * Composes a [WiremockResolver] and uses that by default. But, if a parameter is annotated
 * with [MethodScopeWiremockResolver] it creates a new instance of the [WiremockResolver] extension and
 * manages its lifecycle in the scope of that [ExtensionContext]
 */
class MethodScopeWiremockResolver(
    private val inner: WiremockResolver = WiremockResolver()
) : ParameterResolver by inner,
    AfterEachCallback {

    /**
     * Checks to see if a method the parameter is annotated with [MethodScopedWiremockServer]. If it is, we first verify
     */
    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any {
        return if (parameterContext.isAnnotated(MethodScopedWiremockServer::class.java)) {
            if (!parameterContext.parameter.type.isAssignableFrom(WireMockServer::class.java)) {
                throw IllegalArgumentException("Annotated type must be a WireMockServer")
            }
            return getStore(extensionContext)
                .getOrComputeIfAbsent(Keys.LOCAL_RESOLVER, { WiremockResolver() }, WiremockResolver::class.java)
                .resolveParameter(parameterContext, extensionContext)
        } else {
            inner.resolveParameter(parameterContext, extensionContext)
        }
    }

    override fun afterEach(context: ExtensionContext) {
        getStore(context).get(Keys.LOCAL_RESOLVER, WiremockResolver::class.java)?.afterEach(context)
        inner.afterEach(context)
    }

    /**
     * helper method for get getting a [ExtensionContext.Store] specific to a test method
     */
    private fun getStore(context: ExtensionContext): ExtensionContext.Store =
        context.getStore(ExtensionContext.Namespace.create(javaClass))

    /**
     * Keys for storing and accessing [MethodScopeWiremockResolver]s
     */
    enum class Keys {
        LOCAL_RESOLVER
    }

    /**
     * Decorates a parameter also annotated with [WiremockResolver.Wiremock]
     */
    @Target(allowedTargets = [AnnotationTarget.VALUE_PARAMETER])
    @Retention(AnnotationRetention.RUNTIME)
    annotation class MethodScopedWiremockServer
}
