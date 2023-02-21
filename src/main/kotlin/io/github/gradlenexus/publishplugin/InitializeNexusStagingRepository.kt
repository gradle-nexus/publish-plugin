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
import io.github.gradlenexus.publishplugin.internal.StagingRepositoryDescriptor
import io.github.gradlenexus.publishplugin.internal.StagingRepositoryDescriptorRegistry
import okhttp3.HttpUrl
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class InitializeNexusStagingRepository @Inject constructor(
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
    val repositorySearchMode = objects.property<RepositorySearchMode>().apply {
        set(RepositorySearchMode.FIND_OR_CREATE)
    }

    @Input
    val repositorySearchRegex = objects.property<String>().apply {
        set(repositoryDescription.map { "\\b" + Regex.escape(it) + "(\\s|$)" })
    }

    @Internal
    val stagingRepositoryId = objects.property<String>()

    @TaskAction
    fun createStagingRepo() {
        val repository = repository.get()
        registry.get().getOrNull(repository.name)?.let {
            stagingRepositoryId.set(it.stagingRepositoryId)
            return
        }

        val serverUrl = repository.nexusUrl.get()
        val client = NexusClient(serverUrl, repository.username.orNull, repository.password.orNull, clientTimeout.orNull, connectTimeout.orNull)
        val stagingProfileId = determineStagingProfileId(repository, client)

        var descriptor: StagingRepositoryDescriptor? =
            when (val repositorySearchMode = repositorySearchMode.get()) {
                RepositorySearchMode.CREATE -> null /* handled below */
                RepositorySearchMode.FIND_OR_CREATE,
                RepositorySearchMode.FIND_OR_FAIL,
                RepositorySearchMode.FIND_OR_NULL -> {
                    val descriptionRegex = repositorySearchRegex.get()
                    logger.info(
                        "Searching for an existing staging repository for {} at {}, stagingProfileId '{}' via description regular expression {}",
                        repository.name,
                        serverUrl,
                        stagingProfileId,
                        descriptionRegex
                    )
                    try {
                        val descriptor = client.findStagingRepository(stagingProfileId, Regex(descriptionRegex))
                        logger.lifecycle(
                            "Staging repository for {} at {}, stagingProfileId '{}', descriptionRegex '{}' is '{}'",
                            repository.name,
                            serverUrl,
                            stagingProfileId,
                            descriptionRegex,
                            descriptor.stagingRepositoryId
                        )
                        descriptor
                    } catch (e: NoSuchElementException) {
                        when (repositorySearchMode) {
                            RepositorySearchMode.FIND_OR_FAIL -> throw e
                            RepositorySearchMode.FIND_OR_NULL -> return
                            else -> {
                                logger.info(
                                    "No staging repository found for {} at {}, stagingProfileId '{}', descriptionRegex '{}'",
                                    repository.name,
                                    serverUrl,
                                    stagingProfileId,
                                    descriptionRegex
                                )
                                null
                            }
                        }
                    }
                }
            }

        if (descriptor == null) {
            logger.info("Creating staging repository for {} at {}, stagingProfileId '{}'", repository.name, serverUrl, stagingProfileId)
            descriptor = client.createStagingRepository(stagingProfileId, repositoryDescription.get())
            val consumerUrl = HttpUrl.get(serverUrl)!!.newBuilder().addEncodedPathSegments("repositories/${descriptor.stagingRepositoryId}/content/").build()
            logger.lifecycle("Created staging repository '{}' at {}", descriptor.stagingRepositoryId, consumerUrl)
        }

        stagingRepositoryId.set(descriptor.stagingRepositoryId)
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
