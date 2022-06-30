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

package io.github.gradlenexus.publishplugin

import io.github.gradlenexus.publishplugin.internal.NexusClient
import io.github.gradlenexus.publishplugin.internal.StagingRepositoryDescriptorRegistry
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

open class FindStagingRepository @Inject constructor(
    objects: ObjectFactory,
    extension: NexusPublishExtension,
    repository: NexusRepository,
    private val registry: Provider<StagingRepositoryDescriptorRegistry>
) : AbstractNexusStagingRepositoryTask(objects, extension, repository) {

    @Optional
    @Input
    val packageGroup = objects.property<String>().apply {
        set(extension.packageGroup)
    }

    @Input
    val descriptionRegex = objects.property<String>().apply {
        set(extension.repositoryDescription.map { "\\b" + Regex.escape(it) + "(\\s|$)" })
    }

    @Internal
    val stagingRepositoryId = objects.property<String>()

    init {
        outputs.cacheIf("the task requests data from the external repository, so we don't want to cache it") {
            false
        }
    }

    @TaskAction
    fun findStagingRepository() {
        val repository = repository.get()
        val serverUrl = repository.nexusUrl.get()
        val client = NexusClient(serverUrl, repository.username.orNull, repository.password.orNull, clientTimeout.orNull, connectTimeout.orNull)
        val stagingProfileId = determineStagingProfileId(repository, client)
        logger.info("Fetching staging repositories for {} at {}, stagingProfileId '{}'", repository.name, serverUrl, stagingProfileId)
        val descriptionRegex = descriptionRegex.get()
        val descriptor = client.findStagingRepository(stagingProfileId, Regex(descriptionRegex))
        logger.lifecycle("Staging repository for {} at {}, stagingProfileId '{}', descriptionRegex '{}' is '{}'", repository.name, serverUrl, stagingProfileId, descriptionRegex, descriptor.stagingRepositoryId)
        stagingRepositoryId.set(descriptor.stagingRepositoryId)
        registry.get()[repository.name] = descriptor
    }

    // TODO: Duplication with InitializeNexusStagingRepository
    private fun determineStagingProfileId(repository: NexusRepository, client: NexusClient): String {
        var stagingProfileId = repository.stagingProfileId.orNull
        if (stagingProfileId == null) {
            val packageGroup = packageGroup.get()
            logger.info("No stagingProfileId set, querying for packageGroup '{}'", packageGroup)
            stagingProfileId = client.findStagingProfileId(packageGroup)
                ?: throw GradleException("Failed to find staging profile for package group: $packageGroup")
        }
        return stagingProfileId
    }
}
