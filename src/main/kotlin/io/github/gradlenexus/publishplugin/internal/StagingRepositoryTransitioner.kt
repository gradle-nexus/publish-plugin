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

package io.github.gradlenexus.publishplugin.internal

import io.github.alexo.retrier.Retrier
import io.github.alexo.retrier.Retrier.Strategies.stopAfter
import io.github.gradlenexus.publishplugin.RetryingConfig
import io.github.gradlenexus.publishplugin.StagingRepository
import org.gradle.api.GradleException

//TODO: RetryingConfig pullutes StagingRepositoryTransitioner with Gradle - problematic with unit testing
class StagingRepositoryTransitioner(val nexusClient: NexusClient, val retryingConfig: RetryingConfig) {

    fun effectivelyClose(repoId: String) {
        effectivelyChangeState(repoId, StagingRepository.State.CLOSED, nexusClient::closeStagingRepository)
    }

    fun effectivelyRelease(repoId: String) {
        effectivelyChangeState(repoId, StagingRepository.State.NOT_FOUND, nexusClient::releaseStagingRepository)
    }

    private fun effectivelyChangeState(repoId: String, desiredState: StagingRepository.State, transitionClientRequest: (String) -> Unit) {
        transitionClientRequest.invoke(repoId)
        val readStagingRepository = createRetrier().execute { getStagingRepositoryStateById(repoId) }
        assertRepositoryNotTransitioning(readStagingRepository)
        assertRepositoryInDesiredState(readStagingRepository, desiredState)
    }

    @Suppress("UNCHECKED_CAST")
    private fun createRetrier(): Retrier {
        return Retrier.Builder()
                .withWaitStrategy(Retrier.Strategies.waitConstantly(retryingConfig.delayBetween.get().toMillis()))
                .withStopStrategy(stopAfter(retryingConfig.maxNumber.get()))
                .withResultRetryStrategy({ repo: StagingRepository -> repo.transitioning } as ((Any) -> Boolean))
                .build()
    }

    private fun getStagingRepositoryStateById(repoId: String): StagingRepository {
        val readStagingRepository: StagingRepository = nexusClient.getStagingRepositoryStateById(repoId)
        println("Read staging repository: state: ${readStagingRepository.state}, transitioning: ${readStagingRepository.transitioning}")
        return readStagingRepository
    }

    private fun assertRepositoryNotTransitioning(repository: StagingRepository) {
        if (repository.transitioning) {
            //TODO: Custom exception type
            throw GradleException("Staging repository is still transitioning after defined time. Consider its increament. $repository")
        }
    }

    private fun assertRepositoryInDesiredState(repository: StagingRepository, desiredState: StagingRepository.State) {
        if (repository.state != desiredState) {
            //TODO: Custom exception type
            throw GradleException("Staging repository is not in desired state ($desiredState): $repository. It is unexpected. Please report it " +
                    "to https://github.com/gradle-nexus/publish-plugin/issues/ with '--info' logs")
        }
    }
}
