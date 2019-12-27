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
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.property
import java.net.URI
import javax.inject.Inject

@Suppress("UnstableApiUsage")
open class NexusRepository @Inject constructor(@get:Input val name: String, project: Project) {

    @get:Input
    val nexusUrl: Property<URI> = project.objects.property()

    @get:Input
    val snapshotRepositoryUrl: Property<URI> = project.objects.property()

    @get:Optional
    @get:Input
    val username: Property<String> = project.objects.property<String>().apply {
        set(project.provider { project.findProperty("${name}Username") as? String })
    }

    @get:Optional
    @get:Input
    val password: Property<String> = project.objects.property<String>().apply {
        set(project.provider { project.findProperty("${name}Password") as? String })
    }

    @get:Optional
    @get:Input
    val stagingProfileId: Property<String> = project.objects.property()

    @get:Optional
    @get:Input
    val stagingRepositoryId: Property<String> = project.objects.property()

    internal fun capitalizedName(): String {
        return name.capitalize()
    }
}
