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

import io.codearte.gradle.nexus.NexusStagingExtension
import io.github.gradlenexus.publishplugin.internal.NexusClient
import java.net.URI
import java.time.Duration
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.the

@Suppress("UnstableApiUsage")
open class InitializeNexusStagingRepository @Inject
constructor(objects: ObjectFactory, extension: NexusPublishExtension, repository: NexusRepository, private val serverUrlToStagingRepoUrl: MutableMap<URI, URI>) : DefaultTask() {

    @get:Input
    val serverUrl: Property<URI> = objects.property()

    @get:Optional
    @get:Input
    val username: Property<String> = objects.property()

    @get:Optional
    @get:Input
    val password: Property<String> = objects.property()

    @get:Optional
    @get:Input
    val packageGroup: Property<String> = objects.property()

    @get:Optional
    @get:Input
    val stagingProfileId: Property<String> = objects.property()

    @get:Input
    val repositoryName: Property<String> = objects.property()

    @get:Internal
    val clientTimeout: Property<Duration> = objects.property()

    @get:Internal
    val connectTimeout: Property<Duration> = objects.property()

    init {
        serverUrl.set(repository.nexusUrl)
        username.set(repository.username)
        password.set(repository.password)
        packageGroup.set(extension.packageGroup)
        stagingProfileId.set(repository.stagingProfileId)
        repositoryName.set(repository.name)
        clientTimeout.set(extension.clientTimeout)
        connectTimeout.set(extension.connectTimeout)
        this.onlyIf { extension.useStaging.getOrElse(false) }
    }

    @TaskAction
    fun createStagingRepoAndReplacePublishingRepoUrl() {
        val url = createStagingRepo()
        replacePublishingRepoUrl(url)
    }

    internal fun createStagingRepo(): URI {
        return serverUrlToStagingRepoUrl.computeIfAbsent(serverUrl.get()) { serverUrl ->
            val client = NexusClient(serverUrl, username.orNull, password.orNull, clientTimeout.orNull, connectTimeout.orNull)
            val stagingProfileId = determineStagingProfileId(client)
            logger.info("Creating staging repository for stagingProfileId '{}'", stagingProfileId)
            val stagingRepositoryId = client.createStagingRepository(stagingProfileId)
            project.rootProject.plugins.withId("io.codearte.nexus-staging") {
                val nexusStagingExtension = project.rootProject.the<NexusStagingExtension>()
                try {
                    nexusStagingExtension.stagingRepositoryId.set(stagingRepositoryId)
                } catch (e: NoSuchMethodError) {
                    logger.warn("For increased publishing reliability please update the io.codearte.nexus-staging plugin to at least version 0.20.0.\n" +
                            "If your version is at least 0.20.0, try to update the io.github.gradle-nexus.publish-plugin plugin to its latest version.\n" +
                            "If this also does not make this warning go away, please report an issue for io.github.gradle-nexus.publish-plugin.")
                    logger.debug("getStagingRepositoryId method not found on nexusStagingExtension", e)
                }
            }
            client.getStagingRepositoryUri(stagingRepositoryId)
        }
    }

    private fun determineStagingProfileId(client: NexusClient): String {
        var stagingProfileId = stagingProfileId.orNull
        if (stagingProfileId == null) {
            val packageGroup = packageGroup.get()
            logger.debug("No stagingProfileId set, querying for packageGroup '{}'", packageGroup)
            stagingProfileId = client.findStagingProfileId(packageGroup)
                    ?: throw GradleException("Failed to find staging profile for package group: $packageGroup")
        }
        return stagingProfileId
    }

    private fun replacePublishingRepoUrl(url: URI) {
        val publishing = project.the<PublishingExtension>()
        val repository = publishing.repositories.getByName(repositoryName.get()) as MavenArtifactRepository
        logger.info("Updating URL of publishing repository '{}' to '{}'", repository.name, url)
        repository.setUrl(url.toString())
    }
}
