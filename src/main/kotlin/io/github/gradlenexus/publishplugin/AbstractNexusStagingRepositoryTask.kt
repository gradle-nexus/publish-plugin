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

import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.kotlin.dsl.property
import java.time.Duration
import javax.inject.Inject

@Suppress("UnstableApiUsage")
abstract class AbstractNexusStagingRepositoryTask @Inject
constructor(objects: ObjectFactory, extension: NexusPublishExtension, repository: NexusRepository) : DefaultTask() {

    @Internal
    val clientTimeout = objects.property<Duration>().apply {
        set(extension.clientTimeout)
    }

    @Internal
    val connectTimeout = objects.property<Duration>().apply {
        set(extension.connectTimeout)
    }

    //TODO: Expose externally as interface with getters only
    @Nested
    val repository = objects.property<NexusRepository>().apply {
        set(repository)
    }

    @Input
    val repositoryDescription = objects.property<String>().apply {
        set(extension.repositoryDescription)
    }

    init {
        this.onlyIf { extension.useStaging.getOrElse(false) }
    }
}
