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

package io.github.gradlenexus.publishplugin.internal.modern.transport

import java.net.URI

/**
 * Credential-free repository-base policy; never echoes invalid input in diagnostics.
 */
class DeploymentEndpoint private constructor(private val uri: URI) {

    fun getUri(): URI =
        uri

    companion object {
        @JvmStatic
        fun validate(value: String, allowInsecure: Boolean): DeploymentEndpoint {
            val endpoint: URI
            try {
                endpoint = URI.create(value)
            } catch (_: IllegalArgumentException) {
                throw IllegalArgumentException("NEXUS_INVALID_ENDPOINT")
            }
            val scheme: String? = endpoint.scheme
            require(!(endpoint.getUserInfo() != null || endpoint.rawQuery != null || endpoint.getFragment() != null)) { "NEXUS_CREDENTIAL_BEARING_ENDPOINT_REJECTED" }
            require(!((!"http".equals(scheme, ignoreCase = true) && !"https".equals(scheme, ignoreCase = true)) || endpoint.host == null)) { "NEXUS_INVALID_ENDPOINT" }
            for (part in endpoint.getPath().split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()) require(!(part == "." || part == "..")) { "NEXUS_INVALID_ENDPOINT" }
            require(!("http".equals(scheme, ignoreCase = true) && !allowInsecure)) { "NEXUS_INSECURE_HTTP_REJECTED" }
            return DeploymentEndpoint(if (endpoint.getPath().endsWith("/")) endpoint else URI.create(endpoint.toASCIIString() + "/"))
        }
    }
}
