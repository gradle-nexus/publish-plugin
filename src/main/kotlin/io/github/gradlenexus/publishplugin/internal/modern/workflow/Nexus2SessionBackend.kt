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

package io.github.gradlenexus.publishplugin.internal.modern.workflow

import io.github.gradlenexus.publishplugin.internal.NexusClient
import java.net.URI

/** Nexus 2 acquisition and diagnostics through the shared, execution-local Nexus client. */
internal class Nexus2SessionBackend : RootSessionBackend {
    override fun acquire(request: RootSessionBackend.Request, username: String?, password: String?): RootSessionBackend.Handle {
        val base = RootSessionBackend.base(request.endpoint)
        if (request.resumeId.isNotEmpty()) return handle(base, request.resumeId)
        if (request.profile.isNotEmpty()) RootSessionBackend.identifier(request.profile)
        val matching = try {
            Regex(request.descriptionRegex)
        } catch (_: RuntimeException) {
            throw IllegalArgumentException("NEXUS_INVALID_FIND_PATTERN")
        }
        require((username == null) == (password == null)) { "NEXUS_INCOMPLETE_CREDENTIALS" }
        NexusClient.forSettings(base, username, password, request.connectTimeout, request.requestTimeout).use { client ->
            val profile = request.profile.ifEmpty { profile(client, request.packageGroup) }
            RootSessionBackend.identifier(profile)
            val repository = if (request.mode == "find") {
                client.findStagingRepository(profile, matching)
            } else {
                client.createStagingRepository(profile, request.description)
            }
            return handle(base, repository.stagingRepositoryId)
        }
    }

    companion object {
        @JvmStatic
        fun retrieveProfile(endpoint: URI, packageGroup: String, username: String?, password: String?, connectTimeout: Int, requestTimeout: Int): String {
            require((username == null) == (password == null)) { "NEXUS_INCOMPLETE_CREDENTIALS" }
            val base = RootSessionBackend.base(endpoint)
            NexusClient.forSettings(base, username, password, connectTimeout, requestTimeout).use { client ->
                return profile(client, packageGroup)
            }
        }

        private fun profile(client: NexusClient, packageGroup: String): String =
            RootSessionBackend.identifier(checkNotNull(client.findStagingProfileId(packageGroup)) { "NEXUS_STAGING_PROFILE_NOT_FOUND" })

        private fun handle(base: URI, value: String): RootSessionBackend.Handle {
            val id = RootSessionBackend.identifier(value)
            return RootSessionBackend.Handle("nexus2", base.resolve("staging/deployByRepositoryId/$id/"), id)
        }
    }
}
