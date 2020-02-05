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
import io.github.gradlenexus.publishplugin.RetryingConfig
import io.github.gradlenexus.publishplugin.StagingRepository

open class BasicActionRetrier<R>(retryingConfig: RetryingConfig, stopFunction: (R) -> Boolean) : ActionRetrier<R> {

    @Suppress("UNCHECKED_CAST")
    private val retrier: Retrier = Retrier.Builder()
                    .withWaitStrategy(Retrier.Strategies.waitConstantly(retryingConfig.delayBetween.get().toMillis()))
                    .withStopStrategy(Retrier.Strategies.stopAfter(retryingConfig.maxNumber.get()))
                    .withResultRetryStrategy(stopFunction as ((Any) -> Boolean))
                    .build()

    override fun execute(operationToExecuteWithRetrying: () -> R): R {
        return retrier.execute(operationToExecuteWithRetrying)
    }

    companion object {
        fun retryUntilRepoTransitionIsCompletedRetrier(retryingConfig: RetryingConfig): BasicActionRetrier<StagingRepository> {
            return BasicActionRetrier(retryingConfig) { repo: StagingRepository -> repo.transitioning }
        }
    }
}
