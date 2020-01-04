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

import io.github.gradlenexus.publishplugin.internal.NexusClient
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

//TODO: Extract the same logic from CloseNexusStagingRepository and ReleaseNexusStagingRepository
@Suppress("UnstableApiUsage")
open class ReleaseNexusStagingRepository @Inject
constructor(objects: ObjectFactory, extension: NexusPublishExtension, repository: NexusRepository) :
        AbstractNexusStagingRepositoryTask(objects, extension, repository) {

    @get:Nested
    val stagingRepository: Property<NexusStagingRepository> = objects.property()

    @Option(option = "stagingRepositoryId", description = "stagingRepositoryId to release")
    fun setStagingRepositoryId(stagingRepositoryId: String) {
        stagingRepository.set(NexusStagingRepository(stagingRepositoryId))
    }

    init {
        // TODO: Replace with convention() once only Gradle 5.1+ is supported
        stagingRepository.set(repository.stagingRepository)
    }

    @TaskAction
    fun releaseStagingRepo() {
        val client = NexusClient(repository.get().nexusUrl.get(), repository.get().username.orNull, repository.get().password.orNull, clientTimeout.orNull, connectTimeout.orNull)
        val stagingProfileId = determineStagingProfileId(client) // TODO: Will it update value in extension?
        logger.info("Releasing staging repository with id '{}' for stagingProfileId '{}'", stagingRepository.get().id, stagingProfileId)
        client.releaseStagingRepository(stagingRepository.get().id)
        // TODO: Broken with real Nexus - waiting for effective execution is also required https://github.com/gradle-nexus/publish-plugin/issues/7
    }
}
