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

import java.net.URI
import java.util.HashSet

/**
 * Internal root execution seam. No producer, Gradle model, or service state crosses it.
 */
interface RootSessionBackend {
    @Throws(Exception::class)
    fun acquire(request: Request, username: String?, password: String?): Handle

    data class Request(
        val endpoint: URI,
        val mode: String,
        val resumeId: String,
        val profile: String,
        val packageGroup: String,
        val description: String,
        val descriptionRegex: String,
        val connectTimeout: Int,
        val requestTimeout: Int
    )

    data class Handle(val backend: String, val destination: URI, val repositoryId: String)

    companion object {
        @JvmStatic
        fun forRoute(staging: Boolean): RootSessionBackend =
            if (staging) {
                Nexus2SessionBackend()
            } else {
                object : RootSessionBackend {
                    override fun acquire(request: Request, username: String?, password: String?): Handle =
                        Handle("direct", request.endpoint, "")
                }
            }

        @JvmStatic
        fun validatePolicy(staging: Boolean, modes: MutableList<String>?, resumeId: String) {
            require(modes != null && modes.size <= 1 && modes.all { it == "initialize" || it == "find" } && HashSet(modes).size == modes.size) { "NEXUS_CONFLICTING_SESSION_POLICIES" }
            if (resumeId.isNotEmpty()) identifier(resumeId)
            require(modes.isEmpty() || resumeId.isEmpty()) { "NEXUS_CONFLICTING_SESSION_POLICIES" }
            require(staging || (modes.isEmpty() && resumeId.isEmpty())) { "NEXUS_SESSION_POLICY_UNSUPPORTED_DIRECT_ROUTE" }
        }

        @JvmStatic
        fun identifier(value: String?): String {
            require(value != null && value.matches("[A-Za-z0-9_-]+".toRegex())) { "NEXUS_INVALID_STAGING_IDENTIFIER" }
            return value
        }

        @JvmStatic
        fun base(value: URI): URI =
            URI.create(if (value.toASCIIString().endsWith("/")) value.toASCIIString() else value.toASCIIString() + "/")

        @JvmStatic
        fun validateHandle(handle: Handle, staging: Boolean, configured: URI, expectedResumeId: String) {
            val expected: URI
            if (staging) {
                val id: String = identifier(handle.repositoryId)
                require(handle.backend == "nexus2" && (expectedResumeId.isEmpty() || expectedResumeId == id)) { "NEXUS_WORKFLOW_SESSION_MISMATCH" }
                expected = base(configured).resolve("staging/deployByRepositoryId/$id/")
            } else {
                require(handle.backend == "direct" && handle.repositoryId.isEmpty()) { "NEXUS_WORKFLOW_SESSION_MISMATCH" }
                expected = configured
            }
            require(expected == handle.destination) { "NEXUS_WORKFLOW_SESSION_MISMATCH" }
        }
    }
}
