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

package io.github.gradlenexus.publishplugin.internal

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.Fault
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.zip.GZIPOutputStream

internal class NexusClientTest {
    private lateinit var server: WireMockServer

    @BeforeEach
    fun start() {
        server = WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"))
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop()
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `legacy follows redirects while settings rejects them`(settings: Boolean) {
        server.stubFor(
            WireMock.get(WireMock.urlEqualTo("/staging/profiles"))
                .willReturn(WireMock.aResponse().withStatus(302).withHeader("Location", "/profiles-target"))
        )
        server.stubFor(
            WireMock.get(WireMock.urlEqualTo("/profiles-target"))
                .willReturn(WireMock.okJson("""{"data":[{"name":"org.example","id":"profile"}]}"""))
        )
        client(settings).use { client ->
            if (settings) {
                assertThatThrownBy { client.findStagingProfileId("org.example") }
                    .hasMessage("NEXUS_STAGING_HTTP_FAILURE status=302")
            } else {
                assertThat(client.findStagingProfileId("org.example")).isEqualTo("profile")
            }
        }
        server.verify(if (settings) 0 else 1, WireMock.getRequestedFor(WireMock.urlEqualTo("/profiles-target")))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `legacy retains response bodies in failures while settings sanitizes them`(settings: Boolean) {
        server.stubFor(
            WireMock.get(WireMock.urlEqualTo("/staging/profiles"))
                .willReturn(WireMock.aResponse().withStatus(403).withBody("echoed-private-value"))
        )
        client(settings).use { client ->
            val assertion = assertThatThrownBy { client.findStagingProfileId("org.example") }
            if (settings) {
                assertion.hasMessage("NEXUS_AUTHENTICATION_REJECTED").hasNoCause()
            } else {
                assertion.hasMessageContaining("echoed-private-value").hasMessageContaining("status code 403")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `settings rejects coerced repository identifiers without changing legacy parsing`(settings: Boolean) {
        server.stubFor(
            WireMock.get(WireMock.urlEqualTo("/staging/profile_repositories/profile"))
                .willReturn(WireMock.okJson("""{"data":[{"description":"matched","repositoryId":true}]}"""))
        )
        client(settings).use { client ->
            if (settings) {
                assertThatThrownBy { client.findStagingRepository("profile", Regex("matched")) }
                    .hasMessage("NEXUS_INVALID_STAGING_RESPONSE")
            } else {
                assertThat(client.findStagingRepository("profile", Regex("matched")).stagingRepositoryId).isEqualTo("true")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `settings requires an exact removal identifier while legacy retains substring matching`(settings: Boolean) {
        server.stubFor(
            WireMock.get(WireMock.urlEqualTo("/staging/repository/repo"))
                .willReturn(WireMock.aResponse().withStatus(404).withBody("""{"repositoryId":"repo0"}"""))
        )
        client(settings).use { client ->
            if (settings) {
                assertThatThrownBy { client.getStagingRepositoryStateById("repo") }
                    .hasMessage("NEXUS_TRANSITION_POLL_HTTP_FAILURE status=404")
            } else {
                assertThat(client.getStagingRepositoryStateById("repo").state).isEqualTo(StagingRepository.State.NOT_FOUND)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["start", "close", "promote"])
    fun `settings never replays a rejected post even with immediate retry after`(operation: String) {
        val path = if (operation == "start") "/staging/profiles/profile/start" else "/staging/bulk/$operation"
        server.stubFor(
            WireMock.post(WireMock.urlEqualTo(path)).willReturn(
                WireMock.aResponse()
                    .withStatus(503).withHeader("Retry-After", "0").withBody("echoed-private-value")
            )
        )
        client(true).use { client ->
            assertThatThrownBy {
                when (operation) {
                    "start" -> client.createStagingRepository("profile", "description")
                    "close" -> client.closeStagingRepository("repo", "description")
                    else -> client.releaseStagingRepository("repo", "description")
                }
            }.hasMessage(if (operation == "start") "NEXUS_STAGING_HTTP_FAILURE status=503" else "NEXUS_TRANSITION_HTTP_FAILURE status=503")
                .hasNoCause()
        }
        server.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo(path)))
    }

    @Test
    fun `settings does not replay an ambiguous create after a lost response`() {
        server.stubFor(
            WireMock.post(WireMock.urlEqualTo("/staging/profiles/profile/start"))
                .willReturn(WireMock.aResponse().withFault(Fault.EMPTY_RESPONSE))
        )
        client(true).use { client ->
            assertThatThrownBy { client.createStagingRepository("profile", "description") }
                .hasMessage("NEXUS_STAGING_CREATE_AMBIGUOUS").hasNoCause()
        }
        server.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/staging/profiles/profile/start")))
    }

    @Test
    fun `settings reports a create response timeout as ambiguous without replay`() {
        server.stubFor(
            WireMock.post(WireMock.urlEqualTo("/staging/profiles/profile/start"))
                .willReturn(WireMock.okJson("""{"data":{"stagedRepositoryId":"created"}}""").withFixedDelay(500))
        )
        NexusClient.forSettings(endpoint(), null, null, 2000, 100).use { client ->
            assertThatThrownBy { client.createStagingRepository("profile", "description") }
                .hasMessage("NEXUS_STAGING_CREATE_AMBIGUOUS").hasNoCause()
        }
        server.verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo("/staging/profiles/profile/start")))
    }

    @Test
    fun `legacy five argument constructor retains partial credentials`() {
        server.stubFor(
            WireMock.get(WireMock.urlEqualTo("/staging/profiles"))
                .willReturn(WireMock.okJson("""{"data":[{"name":"org.example","id":"profile"}]}"""))
        )
        NexusClient(endpoint(), "user", null, Duration.ofSeconds(2), Duration.ofSeconds(2)).use { client ->
            assertThat(client.findStagingProfileId("org.example")).isEqualTo("profile")
        }
        server.verify(
            WireMock.getRequestedFor(WireMock.urlEqualTo("/staging/profiles"))
                .withHeader("Authorization", WireMock.equalTo("Basic dXNlcjo="))
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `settings preserves UTF8 credentials and JSON acceptance without changing legacy headers`(settings: Boolean) {
        server.stubFor(
            WireMock.post(WireMock.urlEqualTo("/staging/profiles/profile/start"))
                .willReturn(WireMock.okJson("""{"data":{"stagedRepositoryId":"created"}}"""))
        )
        val username = "café"
        val password = "crème"
        val client = if (settings) {
            NexusClient.forSettings(endpoint(), username, password, 2000, 2000)
        } else {
            NexusClient(endpoint(), username, password, Duration.ofSeconds(2), Duration.ofSeconds(2))
        }
        client.use { assertThat(it.createStagingRepository("profile", "description").stagingRepositoryId).isEqualTo("created") }
        val charset = if (settings) StandardCharsets.UTF_8 else StandardCharsets.ISO_8859_1
        val credentials = Base64.getEncoder().encodeToString("$username:$password".toByteArray(charset))
        server.verify(
            WireMock.postRequestedFor(WireMock.urlEqualTo("/staging/profiles/profile/start"))
                .withHeader("Authorization", WireMock.equalTo("Basic $credentials"))
                .withHeader("Accept", if (settings) WireMock.equalTo("application/json") else WireMock.absent())
        )
    }

    @Test
    fun `settings validates compressed JSON after decoding the HTTP response`() {
        val bytes = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write("""{"data":[{"name":"org.example","id":"profile"}]}""".toByteArray(StandardCharsets.UTF_8)) }
        }.toByteArray()
        server.stubFor(
            WireMock.get(WireMock.urlEqualTo("/staging/profiles"))
                .willReturn(
                    WireMock.aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withHeader("Content-Encoding", "gzip").withBody(bytes)
                )
        )
        client(true).use { assertThat(it.findStagingProfileId("org.example")).isEqualTo("profile") }
    }

    private fun client(settings: Boolean): NexusClient =
        if (settings) {
            NexusClient.forSettings(endpoint(), null, null, 2000, 2000)
        } else {
            NexusClient(endpoint(), null, null, Duration.ofSeconds(2), Duration.ofSeconds(2))
        }

    private fun endpoint(): URI =
        URI("http://127.0.0.1:${server.port()}/")
}
