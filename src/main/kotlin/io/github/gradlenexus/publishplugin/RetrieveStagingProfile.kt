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

import org.gradle.api.GradleException
import org.gradle.api.Incubating
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Diagnostic task for retrieving the [NexusRepository.stagingProfileId] for the [packageGroup] from the provided [NexusRepository] and logging it
 */
@Incubating
abstract class RetrieveStagingProfile : AbstractNexusStagingRepositoryTask() {

    @get:Input
    abstract val packageGroup: Property<String>

    @TaskAction
    fun retrieveStagingProfile() {
        val client = createNexusClient()
        val packageGroup = packageGroup.get()
        val stagingProfileId = client.findStagingProfileId(packageGroup)
            ?: throw GradleException("Failed to find staging profile for package group: $packageGroup")
        logger.lifecycle("Received staging profile id: '{}' for package {}", stagingProfileId, packageGroup)
    }
}
