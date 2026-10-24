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
import io.github.gradlenexus.publishplugin.internal.StagingRepository
import java.net.URI

/** Single transition request followed by bounded state polling through the shared Nexus client. */
internal object Nexus2Transitions {
    @JvmStatic
    fun transition(
        endpoint: URI,
        operation: String,
        id: String,
        description: String,
        username: String?,
        password: String?,
        connectTimeout: Int,
        requestTimeout: Int,
        maxRetries: Int,
        delay: Long
    ) {
        RootSessionBackend.identifier(id)
        require(operation in setOf("close", "release") && maxRetries >= 0 && delay >= 0) { "NEXUS_INVALID_TRANSITION_POLICY" }
        require((username == null) == (password == null)) { "NEXUS_INCOMPLETE_CREDENTIALS" }
        val base = RootSessionBackend.base(endpoint)
        NexusClient.forSettings(base, username, password, connectTimeout, requestTimeout).use { client ->
            if (operation == "close") {
                client.closeStagingRepository(id, description)
            } else {
                client.releaseStagingRepository(id, description)
            }
            val expected = if (operation == "close") StagingRepository.State.CLOSED else StagingRepository.State.RELEASED
            for (attempt in 0..maxRetries) {
                val repository = client.getStagingRepositoryStateById(id)
                if (repository.state == StagingRepository.State.NOT_FOUND) {
                    check(operation == "release") { "NEXUS_TRANSITION_POLL_HTTP_FAILURE status=404" }
                    return
                }
                if (!repository.transitioning) {
                    check(repository.state == expected) { "NEXUS_TRANSITION_WRONG_STATE" }
                    return
                }
                if (attempt < maxRetries && delay > 0) Thread.sleep(delay)
            }
            throw IllegalStateException("NEXUS_TRANSITION_TIMEOUT")
        }
    }
}
