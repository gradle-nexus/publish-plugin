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

import io.github.gradlenexus.publishplugin.internal.InvalidatingStagingRepositoryDescriptorRegistry
import io.github.gradlenexus.publishplugin.internal.NexusClient
import okhttp3.HttpUrl
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

abstract class InitializeNexusStagingRepository @Inject constructor(
    repository: NexusRepository,
    private val registry: Provider<InvalidatingStagingRepositoryDescriptorRegistry>
) : AbstractNexusStagingRepositoryTask(repository) {

    @get:Optional
    @get:Input
    abstract val packageGroup: Property<String>

    @TaskAction
    fun createStagingRepo() {
        val repository = repository.get()
        val serverUrl = repository.nexusUrl.get()
        val client = NexusClient(serverUrl, repository.username.orNull, repository.password.orNull, clientTimeout.orNull, connectTimeout.orNull)
        val stagingProfileId = determineStagingProfileId(repository, client)
        logger.info("Creating staging repository for {} at {}, stagingProfileId '{}'", repository.name, serverUrl, stagingProfileId)
        val descriptor = client.createStagingRepository(stagingProfileId, repositoryDescription.get())
        val consumerUrl = HttpUrl.get(serverUrl)!!.newBuilder().addEncodedPathSegments("repositories/${descriptor.stagingRepositoryId}/content/").build()
        logger.lifecycle("Created staging repository '{}' at {}", descriptor.stagingRepositoryId, consumerUrl)
        registry.get()[repository.name] = descriptor
    }

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
