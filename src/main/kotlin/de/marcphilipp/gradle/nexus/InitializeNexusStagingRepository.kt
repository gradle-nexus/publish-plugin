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

package de.marcphilipp.gradle.nexus

import de.marcphilipp.gradle.nexus.internal.NexusClient
import io.codearte.gradle.nexus.NexusStagingExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.the
import org.slf4j.LoggerFactory
import java.net.URI
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class InitializeNexusStagingRepository @Inject
constructor(project: Project, extension: NexusPublishExtension, private val serverUrlToStagingRepoUrl: MutableMap<URI, URI>) : DefaultTask() {

    @get:Input
    val serverUrl: Property<URI> = project.objects.property()

    @get:Optional
    @get:Input
    val username: Property<String> = project.objects.property()

    @get:Optional
    @get:Input
    val password: Property<String> = project.objects.property()

    @get:Optional
    @get:Input
    val packageGroup: Property<String> = project.objects.property()

    @get:Optional
    @get:Input
    val stagingProfileId: Property<String> = project.objects.property()

    @get:Input
    val repositoryName: Property<String> = project.objects.property()

    init {
        serverUrl.set(extension.serverUrl)
        username.set(extension.username)
        password.set(extension.password)
        packageGroup.set(extension.packageGroup)
        stagingProfileId.set(extension.stagingProfileId)
        repositoryName.set(extension.repositoryName)
        onlyIf { extension.useStaging.getOrElse(false) }
    }

    @TaskAction
    fun createStagingRepoAndReplacePublishingRepoUrl() {
        val url = serverUrlToStagingRepoUrl.computeIfAbsent(serverUrl.get(), { serverUrl ->
            val client = NexusClient(serverUrl, username.orNull, password.orNull)
            var stagingProfileId = stagingProfileId.orNull
            if (stagingProfileId == null) {
                val packageGroup = packageGroup.get()
                logger.debug("No stagingProfileId set, querying for packageGroup '{}'", packageGroup)
                stagingProfileId = client.findStagingProfileId(packageGroup)
                if (stagingProfileId == null) {
                    throw GradleException("Failed to find staging profile for package group: $packageGroup")
                }
            }
            logger.info("Creating staging repository for stagingProfileId '{}'", stagingProfileId)
            val stagingRepositoryId = client.createStagingRepository(stagingProfileId)
            project
                    .rootProject
                    .plugins
                    .withId("io.codearte.nexus-staging", {
                        val nexusStagingExtension = project.rootProject.the<NexusStagingExtension>()
                        try {
                            nexusStagingExtension.stagingRepositoryId.set(stagingRepositoryId)
                        } catch (e: NoSuchMethodError) {
                            logger.warn("For increased publishing reliability please update the io.codearte.nexus-staging plugin to at least version 0.20.0.\n" +
                                    "If your version is at least 0.20.0, try to update the de.marcphilipp.nexus-publish plugin to its latest version.\n" +
                                    "If this also does not make this warning go away, please report an issue for de.marcphilipp.nexus-publish.")
                            logger.debug("getStagingRepositoryId method not found on nexusStagingExtension", e)
                        }
                    })
            client.getStagingRepositoryUri(stagingRepositoryId)
        })
        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        val repository = publishing.repositories.getByName(repositoryName.get()) as MavenArtifactRepository
        logger.info("Updating URL of publishing repository '{}' to '{}'", repository.name, url)
        repository.setUrl(url.toString())
    }

    companion object {

        internal const val NAME = "initializeNexusStagingRepository"

        private val logger = LoggerFactory.getLogger(InitializeNexusStagingRepository::class.java)
    }
}
