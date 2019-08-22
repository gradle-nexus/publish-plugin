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

package de.marcphilipp.gradle.nexus

import io.codearte.gradle.nexus.NexusStagingExtension
import org.gradle.BuildAdapter
import org.gradle.BuildResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

@Suppress("UnstableApiUsage")
class NexusPublishPlugin : Plugin<Project> {

    companion object {
        private val serverUrlToStagingRepoUrl = ConcurrentHashMap<URI, URI>()
    }

    override fun apply(project: Project) {
        project.pluginManager.apply(MavenPublishPlugin::class)

        project.gradle.addBuildListener(object : BuildAdapter() {
            override fun buildFinished(result: BuildResult) {
                serverUrlToStagingRepoUrl.clear()
            }
        })

        val extension = project.extensions.create<NexusPublishExtension>(NexusPublishExtension.NAME, project)

        extension.repositories.all {
            project.tasks.register("publishTo${name.capitalize()}") {
                description = "Publishes all Maven publications produced by this project to the '${this@all.name}' Nexus repository."
                group = PublishingPlugin.PUBLISH_TASK_GROUP
            }
            project.tasks
                    .register<InitializeNexusStagingRepository>("initialize${name.capitalize()}StagingRepository", project.objects, extension, this, serverUrlToStagingRepoUrl)
        }
        extension.repositories.whenObjectRemoved {
            project.tasks.remove(project.tasks.named("publishTo${name.capitalize()}") as Any)
            project.tasks.remove(project.tasks.named("initialize${name.capitalize()}StagingRepository") as Any)
        }

        project.afterEvaluate {
            val nexusRepositories = addMavenRepositories(project, extension)
            nexusRepositories.forEach { (nexusRepo, mavenRepo) ->
                val publishToNexusTask = project.tasks.named("publishTo${nexusRepo.name.capitalize()}")
                val initializeTask = project.tasks.withType(InitializeNexusStagingRepository::class)
                        .named("initialize${nexusRepo.name.capitalize()}StagingRepository")
                configureTaskDependencies(project, publishToNexusTask, initializeTask, mavenRepo)
            }
        }

        project.rootProject.plugins.withId("io.codearte.nexus-staging") {
            val nexusStagingExtension = project.rootProject.the<NexusStagingExtension>()

            extension.packageGroup.set(project.provider {
                if (nexusStagingExtension.packageGroup.isNullOrBlank()) {
                    project.group.toString()
                } else {
                    nexusStagingExtension.packageGroup
                }
            })
            extension.repositories.configureEach {
                stagingProfileId.set(project.provider { nexusStagingExtension.stagingProfileId })
                username.set(project.provider { nexusStagingExtension.username })
                password.set(project.provider { nexusStagingExtension.password })
            }
        }
    }

    private fun addMavenRepositories(project: Project, extension: NexusPublishExtension): Map<NexusRepository, MavenArtifactRepository> {
        return extension.repositories.associateWith { nexusRepo ->
            project.the<PublishingExtension>().repositories.maven {
                name = nexusRepo.name
                url = getRepoUrl(nexusRepo, extension)
                credentials {
                    username = nexusRepo.username.orNull
                    password = nexusRepo.password.orNull
                }
            }
        }
    }

    private fun configureTaskDependencies(project: Project, publishToNexusTask: TaskProvider<Task>, initializeTask: TaskProvider<InitializeNexusStagingRepository>, nexusRepository: MavenArtifactRepository) {
        val publishTasks = project.tasks
                .withType<PublishToMavenRepository>()
                .matching { it.repository == nexusRepository }
        publishToNexusTask.configure { dependsOn(publishTasks) }
        // PublishToMavenRepository tasks may not yet have been initialized
        project.afterEvaluate {
            publishTasks.configureEach {
                dependsOn(initializeTask)
                doFirst { logger.info("Uploading to {}", repository.url) }
            }
        }
    }

    private fun getRepoUrl(nexusRepo: NexusRepository, extension: NexusPublishExtension): URI {
        return if (shouldUseStaging(extension)) nexusRepo.nexusUrl.get() else nexusRepo.snapshotRepositoryUrl.get()
    }

    private fun shouldUseStaging(nexusPublishExtension: NexusPublishExtension): Boolean {
        return nexusPublishExtension.useStaging.get()
    }
}
