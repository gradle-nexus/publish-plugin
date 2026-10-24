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

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import java.time.Duration
import java.util.Collections
import javax.inject.Inject

/**
 * Settings-mode configuration; the existing Project NexusPublishExtension is unchanged.
 * Configure these values directly in the settings script before the plugin's settingsEvaluated
 * callback. That callback finalizes properties and captures immutable producer/root policy.
 * Providers are sampled at this boundary; later upstream changes do not change finalized values,
 * and even an identical late setter may fail. Optional absent values remain absent so final
 * root-project group, name, version and named credential conventions remain lazy.
 */
abstract class NexusPublishSettings @Inject constructor(objects: ObjectFactory) {
    val repositories: NamedDomainObjectContainer<NexusRepositorySettings> =
        objects.domainObjectContainer(NexusRepositorySettings::class.java) { name ->
            objects.newInstance(
                NexusRepositorySettings::class.java,
                name
            )
        }
    val transitionCheckOptions: TransitionCheckSettings = objects.newInstance(TransitionCheckSettings::class.java)

    init {
        clientTimeout.convention(Duration.ofMinutes(5))
        connectTimeout.convention(Duration.ofMinutes(5))
        excludedProjects.convention(Collections.emptySet())
    }

    fun repositories(action: Action<in NamedDomainObjectContainer<NexusRepositorySettings>>) {
        action.execute(repositories)
    }

    fun transitionCheckOptions(action: Action<in TransitionCheckSettings>) {
        action.execute(transitionCheckOptions)
    }

    abstract val useStaging: Property<Boolean>

    abstract val packageGroup: Property<String>

    abstract val repositoryDescription: Property<String>

    abstract val clientTimeout: Property<Duration>

    abstract val connectTimeout: Property<Duration>

    abstract val excludedProjects: SetProperty<String>
}
