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

import io.github.gradlenexus.publishplugin.NexusRepository.PublicationType
import io.github.gradlenexus.publishplugin.internal.InvalidatingStagingRepositoryDescriptorRegistry
import io.github.gradlenexus.publishplugin.internal.StagingRepositoryDescriptorRegistryBuildService
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.artifacts.repositories.AuthenticationSupported
import org.gradle.api.artifacts.repositories.UrlArtifactRepository
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.typeOf
import org.gradle.kotlin.dsl.withType
import org.gradle.util.GradleVersion
import java.time.Duration

class NexusPublishPlugin : Plugin<Project> {

    companion object {
        // visibility for testing
        const val SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME = "closeAndReleaseStagingRepository"
    }

    override fun apply(project: Project) {
        require(project == project.rootProject) {
            "Plugin must be applied to the root project but was applied to ${project.path}"
        }

        require(GradleVersion.current() >= GradleVersion.version("6.0")) {
            "The plugin requires Gradle version 6.0+"
        }

        val registry = createRegistry(project)
        val extension = project.extensions.create<NexusPublishExtension>(NexusPublishExtension.NAME)
        configureExtension(project, extension)
        configureNexusTasks(project, extension, registry)
        configurePublishingForAllProjects(project, extension, registry)
    }

    private fun configureExtension(project: Project, extension: NexusPublishExtension) {
        with(extension) {
            useStaging.convention(project.provider { !project.version.toString().endsWith("-SNAPSHOT") })
            packageGroup.convention(project.provider { project.group.toString() })
            repositoryDescription.convention(project.provider { project.run { "$group:$name:$version" } })
            // Staging repository initialization can take a few minutes on Sonatype Nexus.
            clientTimeout.convention(Duration.ofMinutes(5))
            connectTimeout.convention(Duration.ofMinutes(5))
            transitionCheckOptions.maxRetries.convention(60)
            transitionCheckOptions.delayBetween.convention(Duration.ofSeconds(10))
        }
    }

    private fun createRegistry(rootProject: Project): Provider<InvalidatingStagingRepositoryDescriptorRegistry> {
        if (GradleVersion.current() >= GradleVersion.version("6.1")) {
            return rootProject.gradle.sharedServices.registerIfAbsent("stagingRepositoryUrlRegistry", StagingRepositoryDescriptorRegistryBuildService::class.java) {}.map { it.registry }
        }
        val registry = InvalidatingStagingRepositoryDescriptorRegistry()
        return rootProject.provider { registry }
    }

    private fun configureNexusTasks(rootProject: Project, extension: NexusPublishExtension, registry: Provider<InvalidatingStagingRepositoryDescriptorRegistry>) {
        rootProject.tasks.withType(AbstractNexusStagingRepositoryTask::class.java).configureEach {
            clientTimeout.convention(extension.clientTimeout)
            connectTimeout.convention(extension.connectTimeout)
            repositoryDescription.convention(extension.repositoryDescription)
            useStaging.convention(extension.useStaging)
        }
        rootProject.tasks.withType(AbstractTransitionNexusStagingRepositoryTask::class.java).configureEach {
            transitionCheckOptions.convention(extension.transitionCheckOptions)
        }
        extension.repositories.all {
            username.convention(rootProject.provider { rootProject.findProperty("${name}Username") as? String })
            password.convention(rootProject.provider { rootProject.findProperty("${name}Password") as? String })
            publicationType.convention(PublicationType.MAVEN)

            val repository = this
            val retrieveStagingProfileTask = rootProject.tasks.register<RetrieveStagingProfile>("retrieve${capitalizedName}StagingProfile", rootProject.objects, extension, repository)
            val initializeTask = rootProject.tasks.register<InitializeNexusStagingRepository>(
                "initialize${capitalizedName}StagingRepository",
                rootProject.objects,
                extension,
                repository,
                registry
            )
            val findStagingRepository = rootProject.tasks.register<FindStagingRepository>(
                "find${capitalizedName}StagingRepository",
                rootProject.objects,
                extension,
                repository,
                registry
            )
            findStagingRepository {
                description = "Finds the staging repository for ${repository.name}"
            }
            val closeTask = rootProject.tasks.register<CloseNexusStagingRepository>(
                "close${capitalizedName}StagingRepository",
                rootProject.objects,
                repository,
                registry
            )
            val releaseTask = rootProject.tasks.register<ReleaseNexusStagingRepository>(
                "release${capitalizedName}StagingRepository",
                rootProject.objects,
                repository,
                registry
            )
            val closeAndReleaseTask = rootProject.tasks.register<Task>(
                "closeAndRelease${capitalizedName}StagingRepository"
            )
            retrieveStagingProfileTask {
                description = "Gets and displays a staging profile id for a given repository and package group. This is a diagnostic task to get the value and put it into the NexusRepository configuration closure as stagingProfileId."
            }
            closeTask {
                description = "Closes open staging repository in '${repository.name}' Nexus instance."
                group = PublishingPlugin.PUBLISH_TASK_GROUP
                mustRunAfter(initializeTask)
                mustRunAfter(findStagingRepository)
            }
            releaseTask {
                description = "Releases closed staging repository in '${repository.name}' Nexus instance."
                group = PublishingPlugin.PUBLISH_TASK_GROUP
                mustRunAfter(initializeTask)
                mustRunAfter(findStagingRepository)
                mustRunAfter(closeTask)
            }
            closeAndReleaseTask {
                description = "Closes and releases open staging repository in '${repository.name}' Nexus instance."
                group = PublishingPlugin.PUBLISH_TASK_GROUP
                dependsOn(closeTask, releaseTask)
            }
        }
        extension.repositories.whenObjectRemoved {
            rootProject.tasks.named("initialize${capitalizedName}StagingRepository").configure {
                enabled = false
            }
            rootProject.tasks.named("find${capitalizedName}StagingRepository").configure {
                enabled = false
            }
            rootProject.tasks.named("close${capitalizedName}StagingRepository").configure {
                enabled = false
            }
            rootProject.tasks.named("release${capitalizedName}StagingRepository").configure {
                enabled = false
            }
            rootProject.tasks.named("closeAndRelease${capitalizedName}StagingRepository").configure {
                enabled = false
            }
        }
        rootProject.tasks.register<Task>(SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME) {
            description = "Closes and releases open staging repositories in all configured Nexus instances."
            group = PublishingPlugin.PUBLISH_TASK_GROUP
            enabled = false
        }
    }

    private fun configurePublishingForAllProjects(rootProject: Project, extension: NexusPublishExtension, registry: Provider<InvalidatingStagingRepositoryDescriptorRegistry>) {
        rootProject.afterEvaluate {
            allprojects {
                val publishingProject = this
                plugins.withType<PublishingPlugin> {
                    val nexusRepositories = addPublicationRepositories(publishingProject, extension, registry)
                    nexusRepositories.forEach { (nexusRepo, publicationRepo) ->
                        val publicationType = nexusRepo.publicationType.get()
                        val id = when (publicationType) {
                            PublicationType.IVY -> "ivy-publish"
                            PublicationType.MAVEN -> "maven-publish"
                        }
                        plugins.withId(id) {
                            val initializeTask = rootProject.tasks.named<InitializeNexusStagingRepository>("initialize${nexusRepo.capitalizedName}StagingRepository")
                            val findStagingRepositoryTask = rootProject.tasks.named<FindStagingRepository>("find${nexusRepo.capitalizedName}StagingRepository")
                            val closeTask = rootProject.tasks.named<CloseNexusStagingRepository>("close${nexusRepo.capitalizedName}StagingRepository")
                            val releaseTask = rootProject.tasks.named<ReleaseNexusStagingRepository>("release${nexusRepo.capitalizedName}StagingRepository")
                            val publishAllTask = publishingProject.tasks.register("publishTo${nexusRepo.capitalizedName}") {
                                description = "Publishes all Maven/Ivy publications produced by this project to the '${nexusRepo.name}' Nexus repository."
                                group = PublishingPlugin.PUBLISH_TASK_GROUP
                            }
                            closeTask {
                                mustRunAfter(publishAllTask)
                            }
                            releaseTask {
                                mustRunAfter(publishAllTask)
                            }
                            configureTaskDependencies(publishingProject, initializeTask, findStagingRepositoryTask, publishAllTask, closeTask, releaseTask, publicationRepo, publicationType)
                        }
                    }
                }
            }
            configureSimplifiedCloseAndReleaseTask(rootProject, extension)
        }
    }

    private fun addPublicationRepositories(
        project: Project,
        extension: NexusPublishExtension,
        registry: Provider<InvalidatingStagingRepositoryDescriptorRegistry>
    ): Map<NexusRepository, ArtifactRepository> = extension.repositories.associateWith { nexusRepo ->
        createArtifactRepository(nexusRepo.publicationType.get(), project, nexusRepo, extension, registry)
    }

    private fun createArtifactRepository(
        publicationType: PublicationType,
        project: Project,
        nexusRepo: NexusRepository,
        extension: NexusPublishExtension,
        registry: Provider<InvalidatingStagingRepositoryDescriptorRegistry>
    ): ArtifactRepository = when (publicationType) {
        PublicationType.MAVEN -> project.theExtension<PublishingExtension>().repositories.maven {
            configureArtifactRepo(nexusRepo, project, extension, registry, false)
        }

        PublicationType.IVY -> project.theExtension<PublishingExtension>().repositories.ivy {
            configureArtifactRepo(nexusRepo, project, extension, registry, true)
            if (nexusRepo.ivyPatternLayout.isPresent) {
                nexusRepo.ivyPatternLayout.get().let { this.patternLayout(it) }
            } else {
                this.layout("maven")
            }
        }
    }

    private fun <T> T.configureArtifactRepo(
        nexusRepo: NexusRepository,
        project: Project,
        extension: NexusPublishExtension,
        registry: Provider<InvalidatingStagingRepositoryDescriptorRegistry>,
        provideFallback: Boolean
    ) where T : UrlArtifactRepository, T : ArtifactRepository, T : AuthenticationSupported {
        name = nexusRepo.name
        setUrl(
            project.provider {
                getRepoUrl(nexusRepo, extension, registry, provideFallback, this)
            }
        )
        val allowInsecureProtocol = nexusRepo.allowInsecureProtocol.orNull
        if (allowInsecureProtocol != null) {
            isAllowInsecureProtocol = allowInsecureProtocol
        }
        credentials {
            username = nexusRepo.username.orNull
            password = nexusRepo.password.orNull
        }
    }

    private fun configureTaskDependencies(
        project: Project,
        initializeTask: TaskProvider<InitializeNexusStagingRepository>,
        findStagingRepositoryTask: TaskProvider<FindStagingRepository>,
        publishAllTask: TaskProvider<Task>,
        closeTask: TaskProvider<CloseNexusStagingRepository>,
        releaseTask: TaskProvider<ReleaseNexusStagingRepository>,
        artifactRepo: ArtifactRepository,
        publicationType: PublicationType
    ) {
        val publications = project.theExtension<PublishingExtension>().publications.withType(publicationType.gradleType)
        publications.configureEach {
            val publication = this
            val publishTask = project.tasks.named(
                "publish${publication.name.capitalize()}PublicationTo${artifactRepo.name.capitalize()}Repository",
                publicationType.publishTaskType
            )
            publishTask {
                dependsOn(initializeTask)
                mustRunAfter(findStagingRepositoryTask)
                doFirst {
                    if (artifactRepo is UrlArtifactRepository) {
                        logger.info("Uploading to {}", artifactRepo.url)
                    }
                }
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

    private fun getRepoUrl(
        nexusRepo: NexusRepository,
        extension: NexusPublishExtension,
        registry: Provider<InvalidatingStagingRepositoryDescriptorRegistry>,
        provideFallback: Boolean,
        artifactRepo: ArtifactRepository
    ) = if (extension.useStaging.get()) {
        val descriptorRegistry = registry.get()
        if (provideFallback) {
            descriptorRegistry.invalidateLater(nexusRepo.name, artifactRepo)
            descriptorRegistry.tryGet(nexusRepo.name)?.stagingRepositoryUrl ?: nexusRepo.nexusUrl.get()
        } else {
            descriptorRegistry[nexusRepo.name].stagingRepositoryUrl
        }
    } else {
        nexusRepo.snapshotRepositoryUrl.get()
    }

    private fun configureSimplifiedCloseAndReleaseTask(rootProject: Project, extension: NexusPublishExtension) {
        if (extension.repositories.isNotEmpty()) {
            val closeAndReleaseSimplifiedTask = rootProject.tasks.named(SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME)
            closeAndReleaseSimplifiedTask.configure {
                val repositoryNamesAsString = extension.repositories.joinToString(", ") { "'${it.name}'" }
                val instanceCardinalityAwareString = if (extension.repositories.size > 1) {
                    "instances"
                } else {
                    "instance"
                }
                description = "Closes and releases open staging repositories in the following Nexus $instanceCardinalityAwareString: $repositoryNamesAsString"
                enabled = true
            }
            extension.repositories.all {
                val repositoryCapitalizedName = this.capitalizedName
                val closeAndReleaseTask = rootProject.tasks.named<Task>("closeAndRelease${repositoryCapitalizedName}StagingRepository")
                closeAndReleaseSimplifiedTask.configure {
                    dependsOn(closeAndReleaseTask)
                }
            }
        }
    }
}

private inline fun <reified T : Any> Project.theExtension(): T =
    typeOf<T>().let {
        this.extensions.findByType(it)
            ?: error("The plugin cannot be applied without the publishing plugin")
    }
