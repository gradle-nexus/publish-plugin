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

import java.net.URI
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType

@Suppress("UnstableApiUsage")
class NexusPublishPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "Plugin must be applied to the root project but was applied to ${project.path}"
        }

        val extension = project.extensions.create<NexusPublishExtension>(NexusPublishExtension.NAME, project)
        configureNexusTasks(project, extension)
        configurePublishingForAllProjects(project, extension)
    }

    private fun configureNexusTasks(project: Project, extension: NexusPublishExtension) {
        extension.repositories.all {
            val stagingRepositoryId = project.objects.property<String>()
            val initializeTask = project.tasks.register<InitializeNexusStagingRepository>(
                    "initialize${capitalizedName}StagingRepository", project.objects, extension, this, { id: String -> stagingRepositoryId.set(id) })
            val closeTask = project.tasks.register<CloseNexusStagingRepository>(
                    "close${capitalizedName}StagingRepository", project.objects, extension, this)
            val releaseTask = project.tasks.register<ReleaseNexusStagingRepository>(
                    "release${capitalizedName}StagingRepository", project.objects, extension, this)
            closeTask {
                mustRunAfter(initializeTask)
                this.stagingRepositoryId.set(stagingRepositoryId)
            }
            releaseTask {
                mustRunAfter(initializeTask)
                mustRunAfter(closeTask)
                this.stagingRepositoryId.set(stagingRepositoryId)
            }
        }
        extension.repositories.whenObjectRemoved {
            project.tasks.named("initialize${capitalizedName}StagingRepository").configure {
                enabled = false
            }
            project.tasks.named("close${capitalizedName}StagingRepository").configure {
                enabled = false
            }
            project.tasks.named("release${capitalizedName}StagingRepository").configure {
                enabled = false
            }
        }
    }

    private fun configurePublishingForAllProjects(rootProject: Project, extension: NexusPublishExtension) {
        rootProject.afterEvaluate {
            allprojects {
                val publishingProject = this
                plugins.withId("maven-publish") {
                    val nexusRepositories = addMavenRepositories(publishingProject, extension)
                    nexusRepositories.forEach { (nexusRepo, mavenRepo) ->
                        val initializeTask = rootProject.tasks.withName<InitializeNexusStagingRepository>("initialize${nexusRepo.capitalizedName}StagingRepository")
                        val closeTask = rootProject.tasks.withName<CloseNexusStagingRepository>("close${nexusRepo.capitalizedName}StagingRepository")
                        val releaseTask = rootProject.tasks.withName<ReleaseNexusStagingRepository>("release${nexusRepo.capitalizedName}StagingRepository")
                        val publishAllTask = publishingProject.tasks.register("publishTo${nexusRepo.capitalizedName}") {
                            description = "Publishes all Maven publications produced by this project to the '${nexusRepo.name}' Nexus repository."
                            group = PublishingPlugin.PUBLISH_TASK_GROUP
                        }
                        configureTaskDependencies(publishingProject, initializeTask, publishAllTask, closeTask, releaseTask, mavenRepo)
                    }
                }
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

    private fun configureTaskDependencies(
        project: Project,
        initializeTask: TaskProvider<InitializeNexusStagingRepository>,
        publishAllTask: TaskProvider<Task>,
        closeTask: TaskProvider<CloseNexusStagingRepository>,
        releaseTask: TaskProvider<ReleaseNexusStagingRepository>,
        mavenRepo: MavenArtifactRepository
    ) {
        val mavenPublications = project.the<PublishingExtension>().publications.withType<MavenPublication>()
        mavenPublications.all {
            val publication = this
            val publishTask = project.tasks.withName<PublishToMavenRepository>(
                    "publish${publication.name.capitalize()}PublicationTo${mavenRepo.name.capitalize()}Repository")
            publishTask {
                dependsOn(initializeTask)
                doFirst { logger.info("Uploading to {}", repository.url) }
            }
            publishAllTask {
                dependsOn(publishTask)
            }
            closeTask {
                mustRunAfter(publishTask)
                mustRunAfter(publishAllTask)
            }
            releaseTask {
                mustRunAfter(publishTask)
                mustRunAfter(publishAllTask)
            }
        }
    }

    private fun getRepoUrl(nexusRepo: NexusRepository, extension: NexusPublishExtension): URI {
        return if (shouldUseStaging(extension)) nexusRepo.nexusUrl.get() else nexusRepo.snapshotRepositoryUrl.get()
    }

    private fun shouldUseStaging(nexusPublishExtension: NexusPublishExtension): Boolean {
        return nexusPublishExtension.useStaging.get()
    }

    // For compatibility with 4.10.x (same as built-in `named<T>(String)` extension function)
    private inline fun <reified T : Task> TaskContainer.withName(name: String): TaskProvider<T> {
        return withType<T>().named(name)
    }
}
