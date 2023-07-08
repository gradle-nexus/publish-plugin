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

import io.github.gradlenexus.publishplugin.internal.BasicActionRetrier
import io.github.gradlenexus.publishplugin.internal.InvalidatingStagingRepositoryDescriptorRegistry
import io.github.gradlenexus.publishplugin.internal.NexusClient
import io.github.gradlenexus.publishplugin.internal.StagingRepository
import io.github.gradlenexus.publishplugin.internal.StagingRepositoryTransitioner
import org.gradle.api.Action
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

abstract class AbstractTransitionNexusStagingRepositoryTask(
    repository: NexusRepository,
    registry: Provider<InvalidatingStagingRepositoryDescriptorRegistry>
) : AbstractNexusStagingRepositoryTask(repository) {

    @get:Input
    abstract val stagingRepositoryId: Property<String>

    init {
        this.stagingRepositoryId.set(
            registry.map {
                it[repository.name].stagingRepositoryId
            }
        )
    }

    @get:Internal
    abstract val transitionCheckOptions: Property<TransitionCheckOptions>

    fun transitionCheckOptions(action: Action<in TransitionCheckOptions>) {
        action.execute(transitionCheckOptions.get())
    }

    @TaskAction
    fun transitionStagingRepo() {
        val client = NexusClient(
            repository.get().nexusUrl.get(),
            repository.get().username.orNull,
            repository.get().password.orNull,
            clientTimeout.orNull,
            connectTimeout.orNull
        )
        val retrier = transitionCheckOptions.get().run {
            BasicActionRetrier(maxRetries.get(), delayBetween.get(), StagingRepository::transitioning)
        }
        transitionStagingRepo(StagingRepositoryTransitioner(client, retrier))
    }

    protected abstract fun transitionStagingRepo(repositoryTransitioner: StagingRepositoryTransitioner)
}
