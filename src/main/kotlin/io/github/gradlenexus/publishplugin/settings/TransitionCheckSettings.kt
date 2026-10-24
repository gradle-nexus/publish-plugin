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

package io.github.gradlenexus.publishplugin.settings

import org.gradle.api.provider.Property
import java.time.Duration
import javax.inject.Inject

/**
 * Settings-owned transition policy, finalized with NexusPublishSettings at its settingsEvaluated
 * snapshot. Configure options in the settings script; late setters are not supported.
 */
abstract class TransitionCheckSettings @Inject constructor() {
    init {
        maxRetries.convention(60)
        delayBetween.convention(Duration.ofSeconds(10))
    }

    abstract val maxRetries: Property<Int>

    abstract val delayBetween: Property<Duration>
}
