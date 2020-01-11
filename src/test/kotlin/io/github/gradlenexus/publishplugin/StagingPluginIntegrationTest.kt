/*
 * Copyright 2019 the original author or authors.
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
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.google.gson.Gson
import io.codearte.gradle.nexus.NexusStagingExtension
import io.codearte.gradle.nexus.NexusStagingPlugin
import java.net.URI
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.the
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import ru.lanwen.wiremock.ext.WiremockResolver
import ru.lanwen.wiremock.ext.WiremockResolver.Wiremock

@ExtendWith(WiremockResolver::class)
class StagingPluginIntegrationTest {

    companion object {
        private const val STAGING_PROFILE_ID = "someProfileId"
        private const val STAGED_REPOSITORY_ID = "orgexample-42"
    }

    private val gson = Gson()

    private lateinit var project: Project

    private val ourExtension by lazy {
        project.the<NexusPublishExtension>()
    }

    private val theirExtension by lazy {
        project.the<NexusStagingExtension>()
    }

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().build()
        project.plugins.apply(NexusPublishPlugin::class)
        project.plugins.apply(NexusStagingPlugin::class)
    }

    @Test
    fun `default wiring`() {
        val repository = ourExtension.repositories.create("myRepo")

        theirExtension.packageGroup = "foo"
        theirExtension.stagingProfileId = "12345678"
        theirExtension.username = "bar"
        theirExtension.password = "secret"

        assertThat(ourExtension.packageGroup.orNull).isEqualTo("foo")
        assertThat(repository.stagingProfileId.orNull).isEqualTo("12345678")
        assertThat(repository.username.orNull).isEqualTo("bar")
        assertThat(repository.password.orNull).isEqualTo("secret")
    }

    @Test
    fun `explicit values win`() {
        val repository = ourExtension.repositories.create("myRepo").apply {
            username.set("foo")
            password.set("secret2")
        }

        theirExtension.username = "bar"
        theirExtension.password = "secret1"

        assertThat(repository.password.orNull).isEqualTo("secret2")
        assertThat(repository.username.orNull).isEqualTo("foo")
    }

    @Test
    fun `staged repository id is forwarded`(@Wiremock server: WireMockServer) {
        ourExtension.repositories.create("myRepo").apply {
            nexusUrl.set(URI.create(server.baseUrl()))
            stagingProfileId.set(STAGING_PROFILE_ID)
        }
        server.stubFor(post(urlEqualTo("/staging/profiles/$STAGING_PROFILE_ID/start"))
                .willReturn(aResponse().withBody(gson.toJson(mapOf("data" to mapOf("stagedRepositoryId" to STAGED_REPOSITORY_ID))))))

        val task = project.tasks["initializeMyRepoStagingRepository"] as InitializeNexusStagingRepository
        task.createStagingRepo()

        assertThat(theirExtension.stagingRepositoryId.orNull).isEqualTo(STAGED_REPOSITORY_ID)
    }

    @Test
    fun `stage profile for subgroup is identified`(@Wiremock server: WireMockServer) {
        ourExtension.repositories.create("myRepo").apply {
            nexusUrl.set(URI.create(server.baseUrl()))
        }
        theirExtension.packageGroup = "com.test.abcd"

        server.stubFor(get(urlEqualTo("/staging/profiles"))
                .willReturn(aResponse().withBody(gson.toJson(
                        mapOf("data" to listOf(
                                // zzz is lexically greater than STAGING_PROFILE_ID
                                mapOf("id" to "zzz", "name" to "com"),
                                mapOf("id" to STAGING_PROFILE_ID, "name" to "com.test"),
                                mapOf("id" to "com.test.abcdefg", "name" to "com.test.abcdefg")
                        ))))))

        server.stubFor(post(urlEqualTo("/staging/profiles/$STAGING_PROFILE_ID/start"))
                .willReturn(aResponse().withBody(gson.toJson(mapOf("data" to mapOf("stagedRepositoryId" to STAGED_REPOSITORY_ID))))))

        val task = project.tasks["initializeMyRepoStagingRepository"] as InitializeNexusStagingRepository
        task.createStagingRepo()

        assertThat(theirExtension.stagingRepositoryId.orNull)
                .withFailMessage("Requested package was com.test.abcd, and the longest matching prefix is com.test")
                .isEqualTo(STAGED_REPOSITORY_ID)
    }

    @Test
    fun `uses project group if staging packageGroup is not set`() {
        project.group = "com.acme.foo"
        assertThat(ourExtension.packageGroup.orNull).isEqualTo("com.acme.foo")
    }
}
