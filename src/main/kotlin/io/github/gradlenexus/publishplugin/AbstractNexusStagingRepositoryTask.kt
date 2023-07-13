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

import io.github.gradlenexus.publishplugin.internal.NexusClient
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import java.time.Duration

abstract class AbstractNexusStagingRepositoryTask : DefaultTask() {

    @get:Internal
    abstract val clientTimeout: Property<Duration>

    @get:Internal
    abstract val connectTimeout: Property<Duration>

    // TODO: Expose externally as interface with getters only
    @get:Nested
    abstract val repository: Property<NexusRepository>

    @get:Input
    abstract val repositoryDescription: Property<String>

    @get:Internal
    abstract val useStaging: Property<Boolean>

    init {
        this.onlyIf { useStaging.getOrElse(false) }
    }

    protected fun createNexusClient(): NexusClient {
        val repository = repository.get()
        return NexusClient(
            repository.nexusUrl.get(),
            repository.username.orNull,
            repository.password.orNull,
            clientTimeout.orNull,
            connectTimeout.orNull
        )
    }
}
