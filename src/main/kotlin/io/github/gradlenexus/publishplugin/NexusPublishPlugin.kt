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

import io.github.gradlenexus.publishplugin.internal.StagingRepositoryDescriptorRegistry
import io.github.gradlenexus.publishplugin.internal.StagingRepositoryDescriptorRegistryBuildService
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType
import org.gradle.util.GradleVersion

@Suppress("UnstableApiUsage")
class NexusPublishPlugin : Plugin<Project> {

    companion object {
        //visibility for testing
        const val SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME = "closeAndReleaseStagingRepository"
    }

    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "Plugin must be applied to the root project but was applied to ${project.path}"
        }

        val registry = createRegistry(project)
        val extension = project.extensions.create<NexusPublishExtension>(NexusPublishExtension.NAME, project)
        configureNexusTasks(project, extension, registry)
        configurePublishingForAllProjects(project, extension, registry)
    }

    private fun createRegistry(project: Project): Provider<StagingRepositoryDescriptorRegistry> {
        if (GradleVersion.current() >= GradleVersion.version("6.1")) {
            return project.gradle.sharedServices
                    .registerIfAbsent("stagingRepositoryUrlRegistry", StagingRepositoryDescriptorRegistryBuildService::class.java) {}
                    .map { it.registry }
        }
        val registry = StagingRepositoryDescriptorRegistry()
        return project.provider { registry }
    }

    private fun configureNexusTasks(project: Project, extension: NexusPublishExtension, registry: Provider<StagingRepositoryDescriptorRegistry>) {
        extension.repositories.all {
            val repository = this
            val initializeTask = project.tasks.register<InitializeNexusStagingRepository>(
                    "initialize${capitalizedName}StagingRepository", project.objects, extension, repository, registry)
            val closeTask = project.tasks.register<CloseNexusStagingRepository>(
                    "close${capitalizedName}StagingRepository", project.objects, extension, repository, registry)
            val releaseTask = project.tasks.register<ReleaseNexusStagingRepository>(
                    "release${capitalizedName}StagingRepository", project.objects, extension, repository, registry)
            val closeAndReleaseTask = project.tasks.register<Task>(
                    "closeAndRelease${capitalizedName}StagingRepository")
            closeTask {
                mustRunAfter(initializeTask)
            }
            releaseTask {
                mustRunAfter(initializeTask)
                mustRunAfter(closeTask)
            }
            closeAndReleaseTask {
                dependsOn(closeTask, releaseTask)
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
            project.tasks.named("closeAndRelease${capitalizedName}StagingRepository").configure {
                enabled = false
            }
        }
    }

    private fun configurePublishingForAllProjects(rootProject: Project, extension: NexusPublishExtension, registry: Provider<StagingRepositoryDescriptorRegistry>) {
        rootProject.afterEvaluate {
            allprojects {
                val publishingProject = this
                plugins.withId("maven-publish") {
                    val nexusRepositories = addMavenRepositories(publishingProject, extension, registry)
                    nexusRepositories.forEach { (nexusRepo, mavenRepo) ->
                        val initializeTask = rootProject.tasks.withName<InitializeNexusStagingRepository>("initialize${nexusRepo.capitalizedName}StagingRepository")
                        val closeTask = rootProject.tasks.withName<CloseNexusStagingRepository>("close${nexusRepo.capitalizedName}StagingRepository")
                        val releaseTask = rootProject.tasks.withName<ReleaseNexusStagingRepository>("release${nexusRepo.capitalizedName}StagingRepository")
                        val publishAllTask = publishingProject.tasks.register("publishTo${nexusRepo.capitalizedName}") {
                            description = "Publishes all Maven publications produced by this project to the '${nexusRepo.name}' Nexus repository."
                            group = PublishingPlugin.PUBLISH_TASK_GROUP
                        }
                        closeTask {
                            mustRunAfter(publishAllTask)
                        }
                        releaseTask {
                            mustRunAfter(publishAllTask)
                        }
                        configureTaskDependencies(publishingProject, initializeTask, publishAllTask, closeTask, releaseTask, mavenRepo)
                    }
                }
            }
            if (extension.repositories.isNotEmpty()) {
                val closeAndReleaseSimplifiedTask = rootProject.tasks.register<Task>(SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME)
                extension.repositories.all {
                    val repositoryCapitalizedName = this.capitalizedName
                    val closeAndReleaseTask = rootProject.tasks.withName<Task>("closeAndRelease${repositoryCapitalizedName}StagingRepository")
                    closeAndReleaseSimplifiedTask.configure {
                        dependsOn(closeAndReleaseTask)
                    }
                }
            }
        }
    }

    private fun addMavenRepositories(project: Project, extension: NexusPublishExtension, registry: Provider<StagingRepositoryDescriptorRegistry>): Map<NexusRepository, MavenArtifactRepository> {
        return extension.repositories.associateWith { nexusRepo ->
            project.the<PublishingExtension>().repositories.maven {
                name = nexusRepo.name
                setUrl(project.provider {
                    getRepoUrl(nexusRepo, extension, registry)
                })
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
        mavenPublications.configureEach {
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
            }
            releaseTask {
                mustRunAfter(publishTask)
            }
        }
    }

    private fun getRepoUrl(nexusRepo: NexusRepository, extension: NexusPublishExtension, registry: Provider<StagingRepositoryDescriptorRegistry>) =
        if (extension.useStaging.get()) {
            registry.get()[nexusRepo.name].stagingRepositoryUrl
        } else {
            nexusRepo.snapshotRepositoryUrl.get()
        }

    // For compatibility with 4.10.x (same as built-in `named<T>(String)` extension function)
    private inline fun <reified T : Task> TaskContainer.withName(name: String): TaskProvider<T> {
        return withType<T>().named(name)
    }
}
