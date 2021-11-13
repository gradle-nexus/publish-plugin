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
import org.gradle.kotlin.dsl.container
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import java.time.Duration

@Suppress("UnstableApiUsage")
open class NexusPublishExtension(project: Project) {

    companion object {
        internal const val NAME = "nexusPublishing"
    }

    val useStaging = project.objects.property<Boolean>().apply {
        set(project.provider { !project.version.toString().endsWith("-SNAPSHOT") })
    }

    val packageGroup = project.objects.property<String>().apply {
        set(project.provider { project.group.toString() })
    }

    val repositoryDescription = project.objects.property<String>().apply {
        set(project.provider { project.run { "$group:$name:$version" } })
    }

    val clientTimeout = project.objects.property<Duration>().value(Duration.ofMinutes(5)) //staging repository initialization can take a few minutes on Sonatype Nexus

    val connectTimeout = project.objects.property<Duration>().value(Duration.ofMinutes(5))

    val transitionCheckOptions = project.objects.property<TransitionCheckOptions>().value(project.objects.newInstance(TransitionCheckOptions::class))

    fun transitionCheckOptions(action: Action<in TransitionCheckOptions>) = action.execute(transitionCheckOptions.get())

    val repositories: NexusRepositoryContainer = DefaultNexusRepositoryContainer(project.container(NexusRepository::class) { name ->
        project.objects.newInstance(NexusRepository::class, name, project)
    })

    fun repositories(action: Action<in NexusRepositoryContainer>) = action.execute(repositories)
}
