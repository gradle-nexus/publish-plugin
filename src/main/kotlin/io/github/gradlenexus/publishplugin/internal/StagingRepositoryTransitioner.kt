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

package io.github.gradlenexus.publishplugin.internal

import io.github.gradlenexus.publishplugin.RepositoryTransitionException
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class StagingRepositoryTransitioner(val nexusClient: NexusClient, val retrier: ActionRetrier<StagingRepository>) {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(StagingRepositoryTransitioner::class.java.simpleName)
    }

    fun effectivelyClose(repoId: String, description: String) {
        effectivelyChangeState(repoId, StagingRepository.State.CLOSED) { nexusClient.closeStagingRepository(it, description) }
    }

    fun effectivelyRelease(repoId: String, description: String) {
        effectivelyChangeState(repoId, StagingRepository.State.RELEASED, StagingRepository.State.NOT_FOUND) { nexusClient.releaseStagingRepository(it, description) }
    }

    private fun effectivelyChangeState(repoId: String, vararg desiredStates: StagingRepository.State, transitionClientRequest: (String) -> Unit) {
        transitionClientRequest.invoke(repoId)
        val readStagingRepository = waitUntilTransitionIsDoneOrTimeoutAndReturnLastRepositoryState(repoId)
        assertRepositoryNotTransitioning(readStagingRepository)
        assertRepositoryInDesiredState(readStagingRepository, *desiredStates)
    }

    private fun waitUntilTransitionIsDoneOrTimeoutAndReturnLastRepositoryState(repoId: String) =
            retrier.execute { getStagingRepositoryStateById(repoId) }

    private fun getStagingRepositoryStateById(repoId: String): StagingRepository {
        val readStagingRepository: StagingRepository = nexusClient.getStagingRepositoryStateById(repoId)
        log.info("Current staging repository status: state: ${readStagingRepository.state}, transitioning: ${readStagingRepository.transitioning}")
        return readStagingRepository
    }

    private fun assertRepositoryNotTransitioning(repository: StagingRepository) {
        if (repository.transitioning) {
            throw RepositoryTransitionException("Staging repository is still transitioning after defined time. Consider its increment. $repository")
        }
    }

    private fun assertRepositoryInDesiredState(repository: StagingRepository, vararg desiredStates: StagingRepository.State) {
        if (repository.state !in desiredStates) {
            throw RepositoryTransitionException("Staging repository is not in desired state ${desiredStates.contentToString()}: $repository. It is unexpected. Please check " +
                    "Nexus logs using its web interface - it can be caused by validation rules violation. If not, please report it " +
                    "to https://github.com/gradle-nexus/publish-plugin/issues/ with '--info' logs")
        }
    }
}
