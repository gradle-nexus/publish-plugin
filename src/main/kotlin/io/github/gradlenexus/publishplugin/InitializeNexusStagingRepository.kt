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
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.model.ObjectFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.the
import java.net.URI
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class InitializeNexusStagingRepository @Inject
constructor(objects: ObjectFactory, extension: NexusPublishExtension, repository: NexusRepository, private val serverUrlToStagingRepoUrl: MutableMap<URI, URI>) :
        AbstractNexusStagingRepositoryTask(objects, extension, repository) {

    @TaskAction
    fun createStagingRepoAndReplacePublishingRepoUrl() {
        val url = createStagingRepo()
        replacePublishingRepoUrl(url)
    }

    internal fun createStagingRepo(): URI {
        return serverUrlToStagingRepoUrl.computeIfAbsent(repository.get().nexusUrl.get()) { serverUrl ->
            val client = NexusClient(serverUrl, repository.get().username.orNull, repository.get().password.orNull, clientTimeout.orNull, connectTimeout.orNull)
            val stagingProfileId = determineStagingProfileId(client) // TODO: It would be good to keep/cache value in Extension/Repository
            logger.info("Creating staging repository for stagingProfileId '{}'", stagingProfileId)
            val stagingRepositoryIdAsString = client.createStagingRepository(stagingProfileId)
            keepStagingRepositoryIdInExtension(stagingRepositoryIdAsString)

            project.rootProject.plugins.withId("io.codearte.nexus-staging") {
                val nexusStagingExtension = project.rootProject.the<NexusStagingExtension>()
                try {
                    nexusStagingExtension.stagingRepositoryId.set(stagingRepositoryIdAsString)
                } catch (e: NoSuchMethodError) {
                    logger.warn("For increased publishing reliability please update the io.codearte.nexus-staging plugin to at least version 0.20.0.\n" +
                            "If your version is at least 0.20.0, try to update the io.github.gradle-nexus.publish-plugin plugin to its latest version.\n" +
                            "If this also does not make this warning go away, please report an issue for io.github.gradle-nexus.publish-plugin.")
                    logger.debug("getStagingRepositoryId method not found on nexusStagingExtension", e)
                }
            }
            client.getStagingRepositoryUri(stagingRepositoryIdAsString)
        }
    }

    private fun replacePublishingRepoUrl(url: URI) {
        val publishing = project.the<PublishingExtension>()
        val repository = publishing.repositories.getByName(repository.get().name) as MavenArtifactRepository
        logger.info("Updating URL of publishing repository '{}' to '{}'", repository.name, url)
        repository.setUrl(url.toString())
    }
}
