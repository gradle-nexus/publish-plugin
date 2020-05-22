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

import io.github.gradlenexus.publishplugin.internal.BasicActionRetrier
import io.github.gradlenexus.publishplugin.internal.NexusClient
import io.github.gradlenexus.publishplugin.internal.StagingRepositoryTransitioner
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class CloseNexusStagingRepository @Inject
constructor(objects: ObjectFactory, extension: NexusPublishExtension, repository: NexusRepository) :
        AbstractNexusStagingRepositoryTask(objects, extension, repository) {

    @Input
    val stagingRepositoryId = objects.property<String>()

    @Option(option = "staging-repository-id", description = "staging repository id to close")
    fun setStagingRepositoryId(stagingRepositoryId: String) {
        this.stagingRepositoryId.set(stagingRepositoryId)
    }

    @TaskAction
    fun closeStagingRepo() {
        val client = NexusClient(repository.get().nexusUrl.get(), repository.get().username.orNull, repository.get().password.orNull, clientTimeout.orNull, connectTimeout.orNull)
        val repositoryTransitioner = StagingRepositoryTransitioner(client, BasicActionRetrier.retryUntilRepoTransitionIsCompletedRetrier(repository.get().retrying.get()))
        logger.info("Closing staging repository with id '{}'", stagingRepositoryId.get())
        repositoryTransitioner.effectivelyClose(stagingRepositoryId.get())
        logger.info("Repository with id '{}' effectively closed", stagingRepositoryId.get())
    }
}
