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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI

class RootSessionBackendTest {
    private val endpoint = URI("https://repository.invalid/service/local")

    private fun request(): RootSessionBackend.Request =
        RootSessionBackend.Request(endpoint, "auto", "", "profile", "group", "description", ".*", 2000, 2000)

    @Test
    fun `direct route retains the exact endpoint and has no staging identity`() {
        val handle = RootSessionBackend.forRoute(false).acquire(request(), null, null)
        assertThat(handle).isEqualTo(RootSessionBackend.Handle("direct", endpoint, ""))
        RootSessionBackend.validateHandle(handle, false, endpoint, "")
        assertThat(request()).isEqualTo(request().copy())
        assertThat(request()).isNotEqualTo(request().copy(mode = "find"))
    }

    @Test
    fun `explicit resume bypasses discovery regex and credential checks as before`() {
        val request = request().copy(resumeId = "existing-1", profile = "bad/profile", descriptionRegex = "[")
        val handle = RootSessionBackend.forRoute(true).acquire(request, "partial", null)
        assertThat(handle).isEqualTo(RootSessionBackend.Handle("nexus2", URI("https://repository.invalid/service/local/staging/deployByRepositoryId/existing-1/"), "existing-1"))
        RootSessionBackend.validateHandle(handle, true, endpoint, "existing-1")
    }

    @ParameterizedTest
    @CsvSource("true,none,none", "true,initialize,none", "true,find,none", "true,none,existing-1", "false,none,none")
    fun `supported session policy combinations remain admissible`(staging: Boolean, mode: String, resume: String) {
        RootSessionBackend.validatePolicy(staging, modes(mode), if (resume == "none") "" else resume)
    }

    @ParameterizedTest
    @CsvSource(
        "true,initialize+find,none,NEXUS_CONFLICTING_SESSION_POLICIES",
        "true,find+find,none,NEXUS_CONFLICTING_SESSION_POLICIES",
        "true,other,none,NEXUS_CONFLICTING_SESSION_POLICIES",
        "true,find,existing-1,NEXUS_CONFLICTING_SESSION_POLICIES",
        "false,find,none,NEXUS_SESSION_POLICY_UNSUPPORTED_DIRECT_ROUTE",
        "false,none,existing-1,NEXUS_SESSION_POLICY_UNSUPPORTED_DIRECT_ROUTE",
        "false,find,bad/id,NEXUS_INVALID_STAGING_IDENTIFIER"
    )
    fun `conflicting unsupported and malformed policies preserve diagnostic precedence`(staging: Boolean, mode: String, resume: String, diagnostic: String) {
        assertThatThrownBy { RootSessionBackend.validatePolicy(staging, modes(mode), if (resume == "none") "" else resume) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessage(diagnostic)
    }

    @Test
    fun `missing policy list and invalid identifiers are rejected`() {
        assertThatThrownBy { RootSessionBackend.validatePolicy(true, null, "") }.hasMessage("NEXUS_CONFLICTING_SESSION_POLICIES")
        listOf(null, "", "bad/id", "../repo", "with space").forEach { id ->
            assertThatThrownBy { RootSessionBackend.identifier(id) }.hasMessage("NEXUS_INVALID_STAGING_IDENTIFIER")
        }
    }

    @Test
    fun `handle validation binds backend destination and explicit resume identity`() {
        val handle = RootSessionBackend.Handle("nexus2", URI("https://repository.invalid/service/local/staging/deployByRepositoryId/repo-1/"), "repo-1")
        RootSessionBackend.validateHandle(handle, true, endpoint, "")
        listOf(handle.copy(backend = "other"), handle.copy(destination = URI("https://other.invalid/"))).forEach { invalid ->
            assertThatThrownBy { RootSessionBackend.validateHandle(invalid, true, endpoint, "") }.hasMessage("NEXUS_WORKFLOW_SESSION_MISMATCH")
        }
        assertThatThrownBy { RootSessionBackend.validateHandle(handle, true, endpoint, "other-id") }.hasMessage("NEXUS_WORKFLOW_SESSION_MISMATCH")
        assertThatThrownBy { RootSessionBackend.validateHandle(handle.copy(repositoryId = "bad/id", backend = "other"), true, endpoint, "") }
            .hasMessage("NEXUS_INVALID_STAGING_IDENTIFIER")
        listOf(handle, RootSessionBackend.Handle("direct", endpoint, "repo-1"), RootSessionBackend.Handle("direct", URI("https://other.invalid/"), "")).forEach { invalid ->
            assertThatThrownBy { RootSessionBackend.validateHandle(invalid, false, endpoint, "") }.hasMessage("NEXUS_WORKFLOW_SESSION_MISMATCH")
        }
    }

    @Test
    fun `profile and regex validation precede incomplete credentials`() {
        val backend = RootSessionBackend.forRoute(true)
        assertThatThrownBy { backend.acquire(request().copy(profile = "bad/id", descriptionRegex = "["), "partial", null) }
            .hasMessage("NEXUS_INVALID_STAGING_IDENTIFIER")
        assertThatThrownBy { backend.acquire(request().copy(descriptionRegex = "["), "partial", null) }.hasMessage("NEXUS_INVALID_FIND_PATTERN")
        assertThatThrownBy { backend.acquire(request(), "partial", null) }.hasMessage("NEXUS_INCOMPLETE_CREDENTIALS")
    }

    @ParameterizedTest
    @ValueSource(strings = ["initialize", "find"])
    fun `actual Nexus backend creates or finds once and returns a bound handle`(mode: String) {
        val server = WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"))
        server.start()
        try {
            server.stubFor(
                WireMock.get(WireMock.urlEqualTo("/staging/profiles"))
                    .willReturn(WireMock.okJson("""{"data":[{"name":"group","id":"profile"}]}"""))
            )
            server.stubFor(
                WireMock.post(WireMock.urlEqualTo("/staging/profiles/profile/start"))
                    .willReturn(WireMock.okJson("""{"data":{"stagedRepositoryId":"repo-1"}}"""))
            )
            server.stubFor(
                WireMock.get(WireMock.urlEqualTo("/staging/profile_repositories/profile"))
                    .willReturn(WireMock.okJson("""{"data":[{"description":"description","repositoryId":"repo-1"}]}"""))
            )
            val configured = URI("http://127.0.0.1:${server.port()}/")
            val handle = RootSessionBackend.forRoute(true).acquire(request().copy(endpoint = configured, mode = mode, profile = ""), null, null)
            RootSessionBackend.validateHandle(handle, true, configured, "")
            assertThat(handle.repositoryId).isEqualTo("repo-1")
            assertThat(server.allServeEvents).hasSize(2)
            server.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/staging/profiles")))
            server.verify(if (mode == "find") 1 else 0, WireMock.getRequestedFor(WireMock.urlEqualTo("/staging/profile_repositories/profile")))
            server.verify(
                if (mode == "initialize") 1 else 0,
                WireMock.postRequestedFor(WireMock.urlEqualTo("/staging/profiles/profile/start"))
                    .withRequestBody(WireMock.equalToJson("""{"data":{"description":"description"}}"""))
            )
        } finally {
            server.stop()
        }
    }

    private fun modes(value: String): MutableList<String> =
        if (value == "none") mutableListOf() else value.split('+').toMutableList()
}
