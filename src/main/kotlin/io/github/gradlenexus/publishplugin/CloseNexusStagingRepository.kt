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

@Suppress("UnstableApiUsage")
open class CloseNexusStagingRepository @Inject
constructor(objects: ObjectFactory, extension: NexusPublishExtension, repository: NexusRepository) :
        AbstractNexusStagingRepositoryTask(objects, extension, repository) {

    @get:Nested
    val stagingRepositoryConfig: Property<NexusStagingRepositoryMutableTaskConfig> = objects.property()

    @Option(option = "stagingRepositoryId", description = "stagingRepositoryId to close")
    fun setStagingRepositoryId(stagingRepositoryId: String) {
        stagingRepositoryConfig.get().repositoryId = stagingRepositoryId
    }

    init {
        // TODO: Replace with convention() once only Gradle 5.1+ is supported
        stagingRepositoryConfig.set(repository.stagingRepositoryMutableTaskConfig)
    }

    @TaskAction
    fun closeStagingRepo() {
        val client = NexusClient(repository.get().nexusUrl.get(), repository.get().username.orNull, repository.get().password.orNull, clientTimeout.orNull, connectTimeout.orNull)
        val stagingProfileId = determineAndCacheStagingProfileId(client)
        logger.info("Closing staging repository with id '{}' for stagingProfileId '{}'", stagingRepositoryConfig.get().repositoryId, stagingProfileId)
        client.closeStagingRepository(stagingRepositoryConfig.get().repositoryId!!) //should be checked by @Input
        // TODO: Broken with real Nexus - waiting for effective execution is also required https://github.com/gradle-nexus/publish-plugin/issues/7
    }
}
