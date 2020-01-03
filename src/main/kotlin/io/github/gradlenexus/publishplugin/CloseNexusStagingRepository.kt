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
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class CloseNexusStagingRepository @Inject
constructor(objects: ObjectFactory, extension: NexusPublishExtension, repository: NexusRepository) :
        AbstractNexusStagingRepositoryTask(objects, extension, repository) {

    @get:Nested
    val stagingRepository: Property<NexusStagingRepository> = objects.property()

    //TODO: Bring back an ability to define stagingRepositoryId from a command line (and propagate that value back to NexusStagingRepository for Release*)

    init {
        // TODO: Replace with convention() once only Gradle 5.1+ is supported
        stagingRepository.set(repository.stagingRepository)
    }

    @TaskAction
    fun closeStagingRepo() {
        val client = NexusClient(repository.get().nexusUrl.get(), repository.get().username.orNull, repository.get().password.orNull, clientTimeout.orNull, connectTimeout.orNull)
        val stagingProfileId = determineStagingProfileId(client)
        logger.info("Closing staging repository with id '{}' for stagingProfileId '{}'", stagingRepository.get().id, stagingProfileId)
        client.closeStagingRepository(stagingRepository.get().id)
        // TODO: Broken with real Nexus - waiting for effective execution is also required https://github.com/gradle-nexus/publish-plugin/issues/7
    }
}
