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

import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.kotlin.dsl.property
import java.time.Duration
import javax.inject.Inject

abstract class AbstractNexusStagingRepositoryTask @Inject
constructor(objects: ObjectFactory, repository: NexusRepository) : DefaultTask() {

    @get:Internal
    abstract val clientTimeout: Property<Duration>

    @get:Internal
    abstract val connectTimeout: Property<Duration>

    // TODO: Expose externally as interface with getters only
    @Nested
    val repository = objects.property<NexusRepository>().apply {
        set(repository)
    }

    @get:Input
    abstract val repositoryDescription: Property<String>

    @get:Internal
    internal abstract val useStaging: Property<Boolean>

    init {
        this.onlyIf { useStaging.getOrElse(false) }
    }
}
