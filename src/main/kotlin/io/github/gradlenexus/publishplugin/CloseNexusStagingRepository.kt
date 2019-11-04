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
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class CloseNexusStagingRepository @Inject
constructor(objects: ObjectFactory, extension: NexusPublishExtension, repository: NexusRepository) :
        AbstractNexusStagingRepositoryTask(objects, extension, repository) {

    @get:Input
    val stagingRepositoryId: Property<String> = objects.property()

    init {
        stagingRepositoryId.set(repository.stagingRepositoryId)
    }

    @TaskAction
    fun closeStagingRepo() {
        val client = NexusClient(serverUrl.get(), username.orNull, password.orNull, clientTimeout.orNull, connectTimeout.orNull)
        val stagingProfileId = determineStagingProfileId(client)
        logger.info("Closing staging repository with id '{}' for stagingProfileId '{}'", stagingRepositoryId.get(), stagingProfileId)
        client.closeStagingRepository(stagingRepositoryId.get())
        // TODO: Broken with real Nexus - waiting for effective execution is also required https://github.com/gradle-nexus/publish-plugin/issues/7
    }
}
