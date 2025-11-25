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
import org.gradle.api.reflect.TypeOf.typeOf
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.util.GradleVersion
import java.net.URI
import java.time.Duration

class NexusPublishPlugin : Plugin<Project> {

    companion object {
        private const val SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME = "closeAndReleaseStagingRepositories"
        private const val SIMPLIFIED_CLOSE_TASK_NAME = "closeStagingRepositories"
        private const val SIMPLIFIED_RELEASE_TASK_NAME = "releaseStagingRepositories"
    }

    override fun apply(project: Project) {
        val isRoot = project == project.rootProject

        require(GradleVersion.current() >= GradleVersion.version("6.2")) {
            "io.github.gradle-nexus.publish-plugin requires Gradle version 6.2+"
        }

        val registry = createRegistry(project)
        val extension = project.extensions.create(NexusPublishExtension.NAME, NexusPublishExtension::class.java)
        configureExtension(project, extension)
        configureNexusTasks(project, extension, registry)
        configurePublishingForAllProjects(project, extension, registry, isRoot)
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
            independentProjects.convention(false)
        }
    }

    private fun createRegistry(rootProject: Project): Provider<StagingRepositoryDescriptorRegistryBuildService> =
        rootProject.gradle.sharedServices.registerIfAbsent(
            "stagingRepositoryUrlRegistry",
            StagingRepositoryDescriptorRegistryBuildService::class.java
        ) { }

    private fun configureNexusTasks(
        rootProject: Project,
        extension: NexusPublishExtension,
        registryService: Provider<StagingRepositoryDescriptorRegistryBuildService>
    ) {
        rootProject.tasks.withType(AbstractNexusStagingRepositoryTask::class.java).configureEach {
            it.clientTimeout.convention(extension.clientTimeout)
            it.connectTimeout.convention(extension.connectTimeout)
            it.repositoryDescription.convention(extension.repositoryDescription)
            it.useStaging.convention(extension.useStaging)
            // repository.convention() is set in configureRepositoryTasks().
        }
        rootProject.tasks.withType(AbstractTransitionNexusStagingRepositoryTask::class.java).configureEach {
            it.transitionCheckOptions.convention(extension.transitionCheckOptions)
            it.usesService(registryService)
            it.stagingRepositoryId.convention(registryService.map { service -> service.registry[it.repository.get().name].stagingRepositoryId })
        }
        extension.repositories.all {
            it.username.convention(rootProject.provider { rootProject.findProperty("${it.name}Username") as? String })
            it.password.convention(rootProject.provider { rootProject.findProperty("${it.name}Password") as? String })
            it.publicationType.convention(PublicationType.MAVEN)
            configureRepositoryTasks(rootProject.tasks, extension, it, registryService)
        }
        extension.repositories.whenObjectRemoved { repository ->
            rootProject.tasks.named("initialize${repository.capitalizedName}StagingRepository").configure {
                it.enabled = false
            }
            rootProject.tasks.named("find${repository.capitalizedName}StagingRepository").configure {
                it.enabled = false
            }
            rootProject.tasks.named("close${repository.capitalizedName}StagingRepository").configure {
                it.enabled = false
            }
            rootProject.tasks.named("release${repository.capitalizedName}StagingRepository").configure {
                it.enabled = false
            }
            rootProject.tasks.named("closeAndRelease${repository.capitalizedName}StagingRepository").configure {
                it.enabled = false
            }
        }
        rootProject.tasks.register(SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME) {
            it.group = PublishingPlugin.PUBLISH_TASK_GROUP
            it.description = "Closes and releases open staging repositories in all configured Nexus instances."
        }
        rootProject.tasks.register(SIMPLIFIED_CLOSE_TASK_NAME) {
            it.group = PublishingPlugin.PUBLISH_TASK_GROUP
            it.description = "Closes open staging repositories in all configured Nexus instances."
        }
        rootProject.tasks.register(SIMPLIFIED_RELEASE_TASK_NAME) {
            it.group = PublishingPlugin.PUBLISH_TASK_GROUP
            it.description = "Releases open staging repositories in all configured Nexus instances."
        }
    }

    private fun configureRepositoryTasks(
        tasks: TaskContainer,
        extension: NexusPublishExtension,
        repo: NexusRepository,
        registryService: Provider<StagingRepositoryDescriptorRegistryBuildService>
    ) {
        @Suppress("UNUSED_VARIABLE") // Keep it consistent.
        val retrieveStagingProfileTask = tasks.register(
            "retrieve${repo.capitalizedName}StagingProfile",
            RetrieveStagingProfile::class.java
        ) {
            it.group = PublishingPlugin.PUBLISH_TASK_GROUP
            it.description = "Gets and displays a staging profile id for a given repository and package group. " +
                "This is a diagnostic task to get the value and " +
                "put it into the NexusRepository configuration closure as stagingProfileId."
            it.repository.convention(repo)
            it.packageGroup.convention(extension.packageGroup)
        }
        val initializeTask = tasks.register(
            "initialize${repo.capitalizedName}StagingRepository",
            InitializeNexusStagingRepository::class.java
        ) {
            it.group = PublishingPlugin.PUBLISH_TASK_GROUP
            it.description = "Initializes the staging repository in '${repo.name}' Nexus instance."
            it.registry.set(registryService)
            it.usesService(registryService)
            it.repository.convention(repo)
            it.packageGroup.convention(extension.packageGroup)
        }
        val findStagingRepository = tasks.register(
            "find${repo.capitalizedName}StagingRepository",
            FindStagingRepository::class.java
        ) {
            it.group = PublishingPlugin.PUBLISH_TASK_GROUP
            it.description = "Finds the staging repository for ${repo.name}"
            it.registry.set(registryService)
            it.usesService(registryService)
            it.repository.convention(repo)
            it.packageGroup.convention(extension.packageGroup)
            it.descriptionRegex.convention(extension.repositoryDescription.map { repoDescription -> "\\b" + Regex.escape(repoDescription) + "(\\s|$)" })
        }
        val closeTask = tasks.register(
            "close${repo.capitalizedName}StagingRepository",
            CloseNexusStagingRepository::class.java
        ) {
            it.group = PublishingPlugin.PUBLISH_TASK_GROUP
            it.description = "Closes open staging repository in '${repo.name}' Nexus instance."
            it.repository.convention(repo)
        }
        val releaseTask = tasks.register(
            "release${repo.capitalizedName}StagingRepository",
            ReleaseNexusStagingRepository::class.java
        ) {
            it.group = PublishingPlugin.PUBLISH_TASK_GROUP
            it.description = "Releases closed staging repository in '${repo.name}' Nexus instance."
            it.repository.convention(repo)
        }
        val closeAndReleaseTask = tasks.register(
            "closeAndRelease${repo.capitalizedName}StagingRepository"
        ) {
            it.group = PublishingPlugin.PUBLISH_TASK_GROUP
            it.description = "Closes and releases open staging repository in '${repo.name}' Nexus instance."
        }

        closeTask.configure {
            it.mustRunAfter(initializeTask)
            it.mustRunAfter(findStagingRepository)
        }
        releaseTask.configure {
            it.mustRunAfter(initializeTask)
            it.mustRunAfter(findStagingRepository)
            it.mustRunAfter(closeTask)
        }
        closeAndReleaseTask.configure {
            it.dependsOn(closeTask, releaseTask)
        }
    }

    private fun configurePublishingForAllProjects(
        project: Project,
        extension: NexusPublishExtension,
        registry: Provider<StagingRepositoryDescriptorRegistryBuildService>,
        isRoot: Boolean
    ) {
        project.afterEvaluate {
            require(extension.independentProjects.get() || isRoot) {
                "Plugin must be applied to the root project but was applied to ${project.path}"
            }
            if (extension.independentProjects.get()) {
                configurePublishingProject(project, project, extension, registry)
            } else {
                it.allprojects { publishingProject -> configurePublishingProject(project, publishingProject, extension, registry) }
            }
            configureSimplifiedCloseAndReleaseTasks(project, extension)
        }
    }

    private fun configurePublishingProject(rootProject: Project, publishingProject: Project, extension: NexusPublishExtension, registry: Provider<StagingRepositoryDescriptorRegistryBuildService>) {
        publishingProject.plugins.withType(PublishingPlugin::class.java) {
            val nexusRepositories = addPublicationRepositories(publishingProject, extension, registry)
            nexusRepositories.forEach { (nexusRepo, publicationRepo) ->
                val publicationType = nexusRepo.publicationType.get()
                val id = when (publicationType) {
                    PublicationType.IVY -> "ivy-publish"
                    PublicationType.MAVEN -> "maven-publish"
                    null -> error("Repo publication type must be \"ivy-publish\" or \"maven-publish\"")
                }
                publishingProject.plugins.withId(id) {
                    val initializeTask = rootProject.tasks.named(
                        "initialize${nexusRepo.capitalizedName}StagingRepository",
                        InitializeNexusStagingRepository::class.java
                    )
                    val findStagingRepositoryTask = rootProject.tasks.named(
                        "find${nexusRepo.capitalizedName}StagingRepository",
                        FindStagingRepository::class.java
                    )
                    val closeTask = rootProject.tasks.named(
                        "close${nexusRepo.capitalizedName}StagingRepository",
                        CloseNexusStagingRepository::class.java
                    )
                    val releaseTask = rootProject.tasks.named(
                        "release${nexusRepo.capitalizedName}StagingRepository",
                        ReleaseNexusStagingRepository::class.java
                    )
                    val publishAllTask = publishingProject.tasks.register("publishTo${nexusRepo.capitalizedName}") { task ->
                        task.group = PublishingPlugin.PUBLISH_TASK_GROUP
                        task.description =
                            "Publishes all Maven/Ivy publications produced by this project to the '${nexusRepo.name}' Nexus repository."
                    }
                    closeTask.configure { task ->
                        task.mustRunAfter(publishAllTask)
                    }
                    releaseTask.configure { task ->
                        task.mustRunAfter(publishAllTask)
                    }
                    configureTaskDependencies(
                        publishingProject,
                        initializeTask,
                        findStagingRepositoryTask,
                        publishAllTask,
                        closeTask,
                        releaseTask,
                        publicationRepo,
                        publicationType
                    )
                }
            }
        }
    }

    private fun addPublicationRepositories(
        project: Project,
        extension: NexusPublishExtension,
        registry: Provider<StagingRepositoryDescriptorRegistryBuildService>
    ): Map<NexusRepository, ArtifactRepository> =
        extension.repositories.associateWith { nexusRepo ->
            createArtifactRepository(nexusRepo.publicationType.get(), project, nexusRepo, extension, registry)
        }

    private fun createArtifactRepository(
        publicationType: PublicationType,
        project: Project,
        nexusRepo: NexusRepository,
        extension: NexusPublishExtension,
        registry: Provider<StagingRepositoryDescriptorRegistryBuildService>
    ): ArtifactRepository =
        when (publicationType) {
            PublicationType.MAVEN -> project.theExtension<PublishingExtension>().repositories.maven {
                it.configureArtifactRepo(nexusRepo, extension, registry, false)
            }

            PublicationType.IVY -> project.theExtension<PublishingExtension>().repositories.ivy { repository ->
                repository.configureArtifactRepo(nexusRepo, extension, registry, true)
                if (nexusRepo.ivyPatternLayout.isPresent) {
                    nexusRepo.ivyPatternLayout.get().let { repository.patternLayout(it) }
                } else {
                    repository.layout("maven")
                }
            }
        }

    private fun <T> T.configureArtifactRepo(
        nexusRepo: NexusRepository,
        extension: NexusPublishExtension,
        registry: Provider<StagingRepositoryDescriptorRegistryBuildService>,
        provideFallback: Boolean
    ) where T : UrlArtifactRepository, T : ArtifactRepository, T : AuthenticationSupported {
        name = nexusRepo.name
        setUrl(getRepoUrl(nexusRepo, extension, registry, provideFallback, this))
        val allowInsecureProtocol = nexusRepo.allowInsecureProtocol.orNull
        if (allowInsecureProtocol != null) {
            isAllowInsecureProtocol = allowInsecureProtocol
        }
        credentials {
            it.username = nexusRepo.username.orNull
            it.password = nexusRepo.password.orNull
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
        publications.configureEach { publication ->
            val publishTask = project.tasks.named(
                "publish${publication.name.capitalize()}PublicationTo${artifactRepo.name.capitalize()}Repository",
                publicationType.publishTaskType
            )
            publishTask.configure {
                it.dependsOn(initializeTask)
                it.mustRunAfter(findStagingRepositoryTask)
                it.doFirst { task ->
                    if (artifactRepo is UrlArtifactRepository) {
                        task.logger.info("Uploading to {}", artifactRepo.url)
                    }
                }
            }
            publishAllTask.configure {
                it.dependsOn(publishTask)
            }
            closeTask.configure {
                it.mustRunAfter(publishTask)
            }
            releaseTask.configure {
                it.mustRunAfter(publishTask)
            }
        }
    }

    private fun getRepoUrl(
        nexusRepo: NexusRepository,
        extension: NexusPublishExtension,
        registry: Provider<StagingRepositoryDescriptorRegistryBuildService>,
        provideFallback: Boolean,
        artifactRepo: ArtifactRepository
    ): Provider<URI> =
        extension.useStaging.flatMap { useStaging ->
            if (useStaging) {
                registry.map { it.registry }.map { descriptorRegistry ->
                    if (provideFallback) {
                        descriptorRegistry.invalidateLater(nexusRepo.name, artifactRepo)
                        descriptorRegistry.tryGet(nexusRepo.name)?.stagingRepositoryUrl ?: nexusRepo.nexusUrl.get()
                    } else {
                        descriptorRegistry[nexusRepo.name].stagingRepositoryUrl
                    }
                }
            } else {
                nexusRepo.snapshotRepositoryUrl
            }
        }

    private fun configureSimplifiedCloseAndReleaseTasks(rootProject: Project, extension: NexusPublishExtension) {
        if (extension.repositories.isNotEmpty()) {
            val repositoryNamesAsString = extension.repositories.joinToString(", ") { "'${it.name}'" }
            val instanceCardinalityAwareString = if (extension.repositories.size > 1) {
                "instances"
            } else {
                "instance"
            }
            val closeAndReleaseSimplifiedTask = rootProject.tasks.named(SIMPLIFIED_CLOSE_AND_RELEASE_TASK_NAME) {
                it.description = "Closes and releases open staging repositories in the following Nexus $instanceCardinalityAwareString: $repositoryNamesAsString"
            }
            val closeSimplifiedTask = rootProject.tasks.named(SIMPLIFIED_CLOSE_TASK_NAME) {
                it.description = "Closes open staging repositories in the following Nexus $instanceCardinalityAwareString: $repositoryNamesAsString"
            }
            val releaseSimplifiedTask = rootProject.tasks.named(SIMPLIFIED_RELEASE_TASK_NAME) {
                it.description = "Releases open staging repositories in the following Nexus $instanceCardinalityAwareString: $repositoryNamesAsString"
            }
            extension.repositories.all {
                val repositoryCapitalizedName = it.capitalizedName
                val closeAndReleaseTask = rootProject.tasks.named("closeAndRelease${repositoryCapitalizedName}StagingRepository")
                closeAndReleaseSimplifiedTask.configure { task ->
                    task.dependsOn(closeAndReleaseTask)
                }
                val closeTask = rootProject.tasks.named("close${repositoryCapitalizedName}StagingRepository")
                closeSimplifiedTask.configure { task ->
                    task.dependsOn(closeTask)
                }
                val releaseTask = rootProject.tasks.named("release${repositoryCapitalizedName}StagingRepository")
                releaseSimplifiedTask.configure { task ->
                    task.dependsOn(releaseTask)
                }
            }
        }
    }
}

private inline fun <reified T : Any> Project.theExtension(): T =
    typeOf(T::class.java).let {
        this.extensions.findByType(it)
            ?: error("The plugin cannot be applied without the publishing plugin")
    }
