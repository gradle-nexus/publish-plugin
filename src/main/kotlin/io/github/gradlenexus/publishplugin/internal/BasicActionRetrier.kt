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

import io.github.gradlenexus.publishplugin.RetryingConfig
import net.jodah.failsafe.Failsafe
import net.jodah.failsafe.RetryPolicy
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration

open class BasicActionRetrier<R>(maxRetries: Int, delayBetween: Duration, stopFunction: (R) -> Boolean) : ActionRetrier<R> {

    private val maxAttempts: Int = maxRetries + 1

    private val retrier: RetryPolicy<R> = RetryPolicy<R>()
            //TODO: Some exceptions could be handled separately
            .handleResultIf(stopFunction)
            .onFailedAttempt { event ->
                log.info("Attempt ${event.attemptCount}/$maxAttempts failed with result: ${event.lastResult}")
            }
            .withMaxRetries(maxRetries)
            .withDelay(delayBetween)

    override fun execute(operationToExecuteWithRetrying: () -> R): R {
        return Failsafe.with(retrier).get(operationToExecuteWithRetrying)
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(BasicActionRetrier::class.java.simpleName)

        fun retryUntilRepoTransitionIsCompletedRetrier(retryingConfig: RetryingConfig): BasicActionRetrier<StagingRepository> =
                BasicActionRetrier(retryingConfig.maxRetries.get(), retryingConfig.delayBetween.get()) { repo: StagingRepository ->
                    repo.transitioning
                }
    }
}
