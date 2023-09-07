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
import io.github.gradlenexus.publishplugin.internal.determineStagingProfileId
import org.gradle.api.Incubating
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction

@Incubating
abstract class FindStagingRepository : AbstractNexusStagingRepositoryTask() {

    @get:Internal
    abstract val registry: Property<InvalidatingStagingRepositoryDescriptorRegistry>

    @get:Optional
    @get:Input
    abstract val packageGroup: Property<String>

    @get:Input
    abstract val descriptionRegex: Property<String>

    @get:Internal
    abstract val stagingRepositoryId: Property<String>

    init {
        outputs.cacheIf("the task requests data from the external repository, so we don't want to cache it") {
            false
        }
    }

    @TaskAction
    fun findStagingRepository() {
        val repository = repository.get()
        val serverUrl = repository.nexusUrl.get()
        val client = createNexusClient()
        val stagingProfileId = determineStagingProfileId(client, logger, repository, packageGroup.get())
        logger.info("Fetching staging repositories for {} at {}, stagingProfileId '{}'", repository.name, serverUrl, stagingProfileId)
        val descriptionRegex = descriptionRegex.get()
        val descriptor = client.findStagingRepository(stagingProfileId, Regex(descriptionRegex))
        logger.lifecycle("Staging repository for {} at {}, stagingProfileId '{}', descriptionRegex '{}' is '{}'", repository.name, serverUrl, stagingProfileId, descriptionRegex, descriptor.stagingRepositoryId)
        stagingRepositoryId.set(descriptor.stagingRepositoryId)
        registry.get()[repository.name] = descriptor
    }
}
