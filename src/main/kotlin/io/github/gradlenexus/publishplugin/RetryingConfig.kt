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

import org.gradle.api.Project
import org.gradle.api.tasks.Internal
import org.gradle.kotlin.dsl.property
import java.time.Duration
import javax.inject.Inject

open class RetryingConfig @Inject constructor(project: Project) {

    companion object {
        private val DEFAULT_DELAY_BETWEEN_RETRIES = Duration.ofSeconds(5)
        private const val DEFAULT_MAXIMUM_NUMBER_OF_RETRIES = 30
    }

    @Internal
    val maxRetries = project.objects.property<Int>().apply {
        set(DEFAULT_MAXIMUM_NUMBER_OF_RETRIES)
    }

    @Internal
    val delayBetween = project.objects.property<Duration>().apply {
        set(DEFAULT_DELAY_BETWEEN_RETRIES)
    }
}
