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
import org.gradle.api.DefaultTask
import org.gradle.api.NamedDomainObjectFactory
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.IvyPatternRepositoryLayout
import org.gradle.api.provider.Property
import org.gradle.api.publish.Publication
import org.gradle.api.publish.ivy.IvyPublication
import org.gradle.api.publish.ivy.tasks.PublishToIvyRepository
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.kotlin.dsl.container
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import java.time.Duration
import kotlin.reflect.KClass

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

    // staging repository initialization can take a few minutes on Sonatype Nexus
    val clientTimeout = project.objects.property<Duration>().value(Duration.ofMinutes(5))

    val connectTimeout = project.objects.property<Duration>().value(Duration.ofMinutes(5))

    val transitionCheckOptions = project.objects.property<TransitionCheckOptions>().value(project.objects.newInstance(TransitionCheckOptions::class))

    fun transitionCheckOptions(action: Action<in TransitionCheckOptions>) = action.execute(transitionCheckOptions.get())

    val repositories: NexusRepositoryContainer = project.objects.newInstance(
        DefaultNexusRepositoryContainer::class,
        // `project.container(NexusRepository::class) { name -> ... }`,
        // but in Kotlin 1.3 "New Inference" is not implemented yet, so we have to be explicit.
        // https://kotlinlang.org/docs/whatsnew14.html#new-more-powerful-type-inference-algorithm
        project.container(
            NexusRepository::class,
            NamedDomainObjectFactory { name ->
                project.objects.newInstance(NexusRepository::class, name, project)
            }
        )
    )

    val publicationType: Property<PublicationType> = project.objects.property<PublicationType>().value(PublicationType.MAVEN)

    val ivyPatternLayout: Property<Action<IvyPatternRepositoryLayout>> = project.objects.property<Action<IvyPatternRepositoryLayout>>()
    fun ivyPatternLayout(action: Action<IvyPatternRepositoryLayout>) {
        ivyPatternLayout.set(action)
    }

    fun repositories(action: Action<in NexusRepositoryContainer>) = action.execute(repositories)

    enum class PublicationType(internal val gradleType: KClass<out Publication>, internal val publishTaskType: KClass<out DefaultTask>) {
        MAVEN(MavenPublication::class, PublishToMavenRepository::class),
        IVY(IvyPublication::class, PublishToIvyRepository::class)
    }
}
