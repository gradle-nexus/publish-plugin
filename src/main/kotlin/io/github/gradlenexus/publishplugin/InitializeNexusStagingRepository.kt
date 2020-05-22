/*
 * Copyright 2020 the original author or authors.
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

import io.github.gradlenexus.publishplugin.internal.NexusClient
import org.gradle.api.GradleException
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.model.ObjectFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.property
import java.net.URI
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class InitializeNexusStagingRepository @Inject
constructor(
    objects: ObjectFactory,
    extension: NexusPublishExtension,
    repository: NexusRepository,
    private val stagingRepositoryId: (String) -> Unit
) : AbstractNexusStagingRepositoryTask(objects, extension, repository) {

    @Optional
    @Input
    val packageGroup = objects.property<String>().apply {
        set(extension.packageGroup)
    }

    @TaskAction
    fun createStagingRepoAndReplacePublishingRepoUrl() {
        val url = createStagingRepo()
        replacePublishingRepoUrl(url)
    }

    internal fun createStagingRepo(): URI {
        val serverUrl = repository.get().nexusUrl.get()
        val client = NexusClient(serverUrl, repository.get().username.orNull, repository.get().password.orNull, clientTimeout.orNull, connectTimeout.orNull)
        val stagingProfileId = determineStagingProfileId(client)
        logger.info("Creating staging repository for {} at {}, stagingProfileId '{}'", repository.get().name, serverUrl, stagingProfileId)
        val stagingRepositoryIdAsString = client.createStagingRepository(stagingProfileId)
        stagingRepositoryId.invoke(stagingRepositoryIdAsString)
        return client.getStagingRepositoryUri(stagingRepositoryIdAsString)
    }

    private fun determineStagingProfileId(client: NexusClient): String {
        var stagingProfileId = repository.get().stagingProfileId.orNull
        if (stagingProfileId == null) {
            val packageGroup = packageGroup.get()
            logger.info("No stagingProfileId set, querying for packageGroup '{}'", packageGroup)
            stagingProfileId = client.findStagingProfileId(packageGroup)
                    ?: throw GradleException("Failed to find staging profile for package group: $packageGroup")
        }
        return stagingProfileId
    }

    private fun replacePublishingRepoUrl(url: URI) {
        project.allprojects {
            val publishing = extensions.findByType<PublishingExtension>()
            if (publishing != null) {
                val repository = publishing.repositories.getByName(repository.get().name) as MavenArtifactRepository
                logger.info("Updating URL of publishing repository '{}' to '{}'", repository.name, url)
                repository.setUrl(url.toString())
            }
        }
    }
}
