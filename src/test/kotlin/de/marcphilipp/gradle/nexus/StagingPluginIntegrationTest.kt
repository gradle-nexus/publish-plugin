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
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.google.gson.Gson
import io.codearte.gradle.nexus.NexusStagingExtension
import io.codearte.gradle.nexus.NexusStagingPlugin
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI

@ExtendWith(WireMockExtension::class)
class StagingPluginIntegrationTest {

    companion object {
        private const val STAGING_PROFILE_ID = "someProfileId"
        private const val STAGED_REPOSITORY_ID = "orgexample-42"
    }

    private val gson = Gson()

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().build()
        project.plugins.apply(NexusPublishPlugin::class.java)
        project.plugins.apply(NexusStagingPlugin::class.java)
    }

    @Test
    fun `default wiring`() {
        val ourExtension = project.extensions.getByType(NexusPublishExtension::class.java)
        val theirExtension = project.extensions.getByType(NexusStagingExtension::class.java)

        theirExtension.packageGroup = "foo"
        assertThat(ourExtension.packageGroup.orNull).isEqualTo("foo")

        theirExtension.stagingProfileId = "12345678"
        assertThat(ourExtension.stagingProfileId.orNull).isEqualTo("12345678")

        theirExtension.username = "bar"
        assertThat(ourExtension.username.orNull).isEqualTo("bar")

        theirExtension.password = "secret"
        assertThat(ourExtension.password.orNull).isEqualTo("secret")
    }

    @Test
    fun `explicit values win`() {
        val ourExtension = project.extensions.getByType(NexusPublishExtension::class.java)
        val theirExtension = project.extensions.getByType(NexusStagingExtension::class.java)

        ourExtension.username.set("foo")
        theirExtension.username = "bar"
        assertThat(ourExtension.username.orNull).isEqualTo("foo")

        theirExtension.password = "secret1"
        ourExtension.password.set("secret2")
        assertThat(ourExtension.password.orNull).isEqualTo("secret2")
    }

    @Test
    fun `staged repository id is forwarded`(server: WireMockServer) {
        val ourExtension = project.extensions.getByType(NexusPublishExtension::class.java)
        val theirExtension = project.extensions.getByType(NexusStagingExtension::class.java)

        ourExtension.serverUrl.set(URI.create(server.baseUrl()))
        ourExtension.stagingProfileId.set(STAGING_PROFILE_ID)

        server.stubFor(post(urlEqualTo("/staging/profiles/$STAGING_PROFILE_ID/start"))
                .willReturn(aResponse().withBody(gson.toJson(mapOf("data" to mapOf("stagedRepositoryId" to STAGED_REPOSITORY_ID))))))

        (project.getTasksByName("initializeNexusStagingRepository", false).first() as InitializeNexusStagingRepository).createStagingRepoAndReplacePublishingRepoUrl()
        assertThat(theirExtension.stagingRepositoryId.orNull).isEqualTo(STAGED_REPOSITORY_ID)
    }

}
