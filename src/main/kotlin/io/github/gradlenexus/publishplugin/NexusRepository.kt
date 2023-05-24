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

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.IvyPatternRepositoryLayout
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.property
import java.net.URI
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class NexusRepository @Inject constructor(@Input val name: String, project: Project) {

    @Input
    val nexusUrl = project.objects.property<URI>()

    @Input
    val snapshotRepositoryUrl = project.objects.property<URI>()

    @Input
    val publicationType: Property<NexusPublishExtension.PublicationType> = project.objects.property<NexusPublishExtension.PublicationType>().value(NexusPublishExtension.PublicationType.MAVEN)

    @Internal
    val username = project.objects.property<String>().apply {
        set(project.provider { project.findProperty("${name}Username") as? String })
    }

    @Internal
    val password = project.objects.property<String>().apply {
        set(project.provider { project.findProperty("${name}Password") as? String })
    }

    @Internal
    val allowInsecureProtocol = project.objects.property<Boolean>()

    @Optional
    @Input
    val stagingProfileId = project.objects.property<String>()

    @get:Internal
    internal val capitalizedName by lazy { name.capitalize() }

    @Optional
    @Input
    val ivyPatternLayout: Property<Action<IvyPatternRepositoryLayout>> = project.objects.property<Action<IvyPatternRepositoryLayout>>()
    fun ivyPatternLayout(action: Action<IvyPatternRepositoryLayout>) {
        ivyPatternLayout.set(action)
    }
}
