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
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType

@Suppress("UnstableApiUsage")
class NexusPublishPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "Plugin must be applied to the root project"
        }

        val extension = project.extensions.create<NexusPublishExtension>(NexusPublishExtension.NAME, project)
        configureTasks(project, extension)
        configureTaskDependenciesForAllProjects(project, extension)
    }

    private fun configureTaskDependenciesForAllProjects(rootProject: Project, extension: NexusPublishExtension) {
        rootProject.afterEvaluate {
            rootProject.allprojects {
                val publishingProject = this
                pluginManager.withPlugin("maven-publish") {
                    publishingProject.afterEvaluate {
                        val nexusRepositories = addMavenRepositories(publishingProject, extension)
                        nexusRepositories.forEach { (nexusRepo, mavenRepo) ->
                            val initializeTask = rootProject.tasks.withType(InitializeNexusStagingRepository::class)
                                    .named("initialize${nexusRepo.capitalizedName()}StagingRepository")
                            val closeTask = rootProject.tasks.withType(CloseNexusStagingRepository::class)
                                    .named("close${nexusRepo.capitalizedName()}StagingRepository")
                            val releaseTask = rootProject.tasks.withType(ReleaseNexusStagingRepository::class)
                                    .named("release${nexusRepo.capitalizedName()}StagingRepository")
                            configureTaskDependencies(publishingProject, initializeTask, closeTask, releaseTask, mavenRepo)
                        }
                    }
                }
            }
        }
    }

    private fun configureTasks(project: Project, extension: NexusPublishExtension) {
        extension.repositories.all {
            val stagingRepositoryId = project.objects.property<String>()
            project.tasks
                    .register<InitializeNexusStagingRepository>("initialize${capitalizedName()}StagingRepository", project.objects, extension, this, { id: String -> stagingRepositoryId.set(id) })
            project.tasks
                    .register<CloseNexusStagingRepository>("close${capitalizedName()}StagingRepository", project.objects, extension, this)
                    .configure {
                        this.stagingRepositoryId.set(stagingRepositoryId)
                    }
            project.tasks
                    .register<ReleaseNexusStagingRepository>("release${capitalizedName()}StagingRepository", project.objects, extension, this)
                    .configure {
                        this.stagingRepositoryId.set(stagingRepositoryId)
                    }
        }
        extension.repositories.whenObjectRemoved {
            project.tasks.remove(project.tasks.named("initialize${capitalizedName()}StagingRepository") as Any)
            project.tasks.remove(project.tasks.named("close${capitalizedName()}StagingRepository") as Any)
            project.tasks.remove(project.tasks.named("release${capitalizedName()}StagingRepository") as Any)
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
        closeTask: TaskProvider<CloseNexusStagingRepository>,
        releaseTask: TaskProvider<ReleaseNexusStagingRepository>,
        nexusRepository: MavenArtifactRepository
    ) {
        val publishTasks = project.tasks
                .withType<PublishToMavenRepository>()
                .matching { it.repository == nexusRepository }
        project.tasks.register("publishTo${nexusRepository.name.capitalize()}") {
            description = "Publishes all Maven publications produced by this project to the '${nexusRepository.name}' Nexus repository."
            group = PublishingPlugin.PUBLISH_TASK_GROUP
            dependsOn(publishTasks)
        }
        // PublishToMavenRepository tasks may not yet have been initialized
        project.afterEvaluate {
            publishTasks.configureEach {
                dependsOn(initializeTask)
                doFirst { logger.info("Uploading to {}", repository.url) }
            }
        }
        closeTask.configure {
            mustRunAfter(initializeTask)
            mustRunAfter(publishTasks)
        }
        releaseTask.configure {
            mustRunAfter(initializeTask)
            mustRunAfter(closeTask)
            mustRunAfter(publishTasks)
        }
    }

    private fun getRepoUrl(nexusRepo: NexusRepository, extension: NexusPublishExtension): URI {
        return if (shouldUseStaging(extension)) nexusRepo.nexusUrl.get() else nexusRepo.snapshotRepositoryUrl.get()
    }

    private fun shouldUseStaging(nexusPublishExtension: NexusPublishExtension): Boolean {
        return nexusPublishExtension.useStaging.get()
    }
}
