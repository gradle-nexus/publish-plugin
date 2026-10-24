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

package io.github.gradlenexus.publishplugin.internal.modern.workflow

import io.github.gradlenexus.publishplugin.internal.modern.BootstrapSpec
import io.github.gradlenexus.publishplugin.internal.modern.BootstrapSpec.RepositoryRoute
import io.github.gradlenexus.publishplugin.internal.modern.ProducerState
import io.github.gradlenexus.publishplugin.internal.modern.RootCoordinator
import io.github.gradlenexus.publishplugin.internal.modern.bundle.NativeTaskNames
import io.github.gradlenexus.publishplugin.internal.modern.bundle.PrepareBundle
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.build.event.BuildEventsListenerRegistry
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.function.Consumer
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * Root-local configuration only. Tasks receive detached properties, never this plugin or root model.
 */
class RootWorkflowPlugin @Inject constructor(private val registry: BuildEventsListenerRegistry) : Plugin<Project> {

    override fun apply(root: Project) {
        val bootstrap: BootstrapSpec = root.extensions.getByType(ProducerState::class.java).getBootstrap()
        val coordinator: RootCoordinator = root.extensions.getByType(RootCoordinator::class.java)
        val closeTasks: MutableList<String> = ArrayList<String>()
        val releaseTasks: MutableList<String> = ArrayList<String>()
        val combinedTasks: MutableList<String> = ArrayList<String>()
        for (route in bootstrap.repositories) {
            val repository: RootCoordinator.Repository = coordinator.repositories.stream().filter { value: RootCoordinator.Repository ->
                value.getName() == route.getName()
            }
                .findFirst().orElseThrow()
            configure(root, bootstrap, route, coordinator, repository)
            val suffix: String = BootstrapSpec.capitalize(route.getName()) + "StagingRepository"
            closeTasks.add(":close$suffix")
            releaseTasks.add(":release$suffix")
            combinedTasks.add(":closeAndRelease$suffix")
        }
        root.tasks.register("closeStagingRepositories") { task ->
            publishing(
                task,
                "Closes selected staging repositories in all configured Nexus repositories."
            )
            task.dependsOn(closeTasks)
        }
        root.tasks.register("releaseStagingRepositories") { task ->
            publishing(
                task,
                "Releases selected staging repositories in all configured Nexus repositories."
            )
            task.dependsOn(releaseTasks)
        }
        root.tasks.register("closeAndReleaseStagingRepositories") { task ->
            publishing(
                task,
                "Closes and releases selected staging repositories in all configured Nexus repositories."
            )
            task.dependsOn(combinedTasks)
        }
    }

    private fun configure(root: Project, bootstrap: BootstrapSpec, route: RepositoryRoute, coordinator: RootCoordinator, repository: RootCoordinator.Repository) {
        val scope: String = route.getScope()
        val format: String = route.format
        val capitalFormat: String = BootstrapSpec.capitalize(format)
        root.tasks.register("retrieve" + BootstrapSpec.capitalize(route.getName()) + "StagingProfile", StagingProfileDiagnostic::class.java) { task ->
            task.group = "publishing"
            task.description =
                "Gets and displays the staging profile ID for this repository and package group; does not create a staging repository."
            task.repositoryAlias.set(route.getName())
            task.packageGroup.convention(coordinator.packageGroup)
            task.useStaging.set(coordinator.useStaging)
            task.insecure.set(repository.getSpec().allowInsecureProtocol)
            task.endpoint.set(repository.getSpec().nexusUrl)
            task.username.set(repository.username)
            task.password.set(repository.password)
            task.connectTimeout.set(Math.toIntExact(coordinator.connectTimeout.toMillis()))
            task.requestTimeout.set(Math.toIntExact(coordinator.clientTimeout.toMillis()))
        }
        val intents: MutableMap<String, String> = LinkedHashMap<String, String>()
        val preflights: MutableMap<String, String> = LinkedHashMap<String, String>()
        val resets: MutableMap<String, String> = LinkedHashMap<String, String>()
        val prepares: MutableMap<String, String> = LinkedHashMap<String, String>()
        val rootNames: MutableSet<String> = LinkedHashSet<String>(java.util.List.of(scope + "_reset", scope + "_checkpoint", scope + "_validate", scope + "_session", scope + "_upload", scope + "_modeCheckpoint", scope + "_operationIntent_initialize", scope + "_operationIntent_find", scope + "_operationIntent_close", scope + "_operationIntent_release", scope + "_beforeClose", scope + "_beforeRelease"))
        val operationPaths: MutableMap<String, String> = LinkedHashMap<String, String>()
        val operationIntents: MutableMap<String, String> = LinkedHashMap<String, String>()
        val capitalRepository: String = BootstrapSpec.capitalize(route.getName())
        operationPaths["initialize"] = ":initialize" + capitalRepository + "StagingRepository"
        operationPaths["find"] = ":find" + capitalRepository + "StagingRepository"
        operationPaths["close"] = ":close" + capitalRepository + "StagingRepository"
        operationPaths["release"] = ":release" + capitalRepository + "StagingRepository"
        operationPaths.keys.forEach(
            Consumer { mode: String ->
                operationIntents[mode] = ":" + scope + "_operationIntent_" + mode
            }
        )
        val producerPaths: MutableSet<String> = LinkedHashSet<String>()
        val exportNamespaces: MutableSet<String> = LinkedHashSet<String>()
        val producerIdentities: MutableMap<String, String> = LinkedHashMap<String, String>()
        for (index in bootstrap.getProjectPaths().indices) {
            val path: String = bootstrap.getProjectPaths()[index]
            if (bootstrap.getExclusions().contains(path)) continue
            val unit = "p$index"
            producerIdentities[unit] = path
            exportNamespaces.add(NativeTaskNames.exportNamespace(path, format))
            intents[unit] = ":" + scope + "_intent" + index
            preflights[unit] = ":" + scope + "_preflight" + index
            val prefix: String = if (path == ":") ":" else "$path:"
            resets[unit] = prefix + "resetNexus" + capitalFormat + "Bundle"
            prepares[unit] = prefix + "prepareNexus" + capitalFormat + "Bundle"
            producerPaths.add(resets.getValue(unit))
            producerPaths.add(prepares.getValue(unit))
            rootNames.add(scope + "_intent" + index)
            rootNames.add(scope + "_preflight" + index)
        }
        val events: Provider<CompletionObserver> = root.gradle.sharedServices.registerIfAbsent(scope + "_observation", CompletionObserver::class.java) { registration ->
            registration.getParameters().getRootTaskNames().set(rootNames)
            registration.getParameters().getProducerTaskPaths().set(producerPaths)
            registration.getParameters().getNativeExportNamespaces().set(exportNamespaces)
            registration.getParameters().getPublicOperationPaths().set(operationPaths.values)
        }
        registry.onTaskCompletion(events)
        val reset: TaskProvider<WorkflowReset> = root.tasks.register(scope + "_reset", WorkflowReset::class.java) { task ->
            task.observation.set(events)
            task.usesService(events)
            task.workspace.set(root.layout.buildDirectory.dir("nexus-publishing/workflows/$scope"))
        }
        val intentTasks: MutableList<TaskProvider<*>> = ArrayList<TaskProvider<*>>()
        val preflightTasks: MutableList<TaskProvider<*>> = ArrayList<TaskProvider<*>>()
        for (index in bootstrap.getProjectPaths().indices) {
            val path: String = bootstrap.getProjectPaths()[index]
            val unit = "p$index"
            if (bootstrap.getExclusions().contains(path)) continue
            intentTasks.add(
                root.tasks.register(scope + "_intent" + index, WorkflowIntent::class.java) { task ->
                    task.observation.set(events)
                    task.usesService(events)
                    task.unit.set(unit)
                    task.dependsOn(reset)
                }
            )
            val input: Any
            if (path == ":") {
                val prepare: TaskProvider<PrepareBundle> = root.tasks.named("prepareNexus" + capitalFormat + "Bundle", PrepareBundle::class.java)
                input = root.files(prepare.flatMap { obj: PrepareBundle -> obj.directory }).builtBy(root.tasks.named("resetNexus" + capitalFormat + "Bundle"), prepare)
            } else {
                input = root.configurations.getByName("nexus" + capitalFormat + "BundleInput" + index)
            }
            val bundle: Any = input
            preflightTasks.add(
                root.tasks.register(scope + "_preflight" + index, WorkflowPreflight::class.java) { task ->
                    task.observation.set(events)
                    task.usesService(events)
                    task.producerPath.set(path)
                    task.format.set(format)
                    task.bundles.from(bundle)
                    task.workspace.set(reset.flatMap { obj: WorkflowReset -> obj.workspace })
                    task.staged.set(reset.flatMap { value -> value.workspace.dir("staged/$unit") })
                    task.dependsOn(reset)
                }
            )
        }
        val operationIntentTasks: MutableList<TaskProvider<WorkflowIntent>> = ArrayList<TaskProvider<WorkflowIntent>>()
        operationIntents.forEach { (mode: String, path: String) ->
            operationIntentTasks.add(
                root.tasks.register(path.substring(1), WorkflowIntent::class.java) { task ->
                    task.observation.set(events)
                    task.usesService(events)
                    task.unit.set(mode)
                    task.dependsOn(reset)
                }
            )
        }
        val checkpoint: TaskProvider<Task> = root.tasks.register(scope + "_checkpoint") { task ->
            task.dependsOn(reset)
            task.mustRunAfter(intentTasks, preflightTasks, operationIntentTasks)
        }
        val validate: TaskProvider<WorkflowValidate> = root.tasks.register(scope + "_validate", WorkflowValidate::class.java) { task ->
            policy(
                task,
                events,
                reset,
                route,
                coordinator,
                repository,
                bootstrap.isSuppressIvyModuleMetadata()
            )
            task.checkpoint.set(":" + checkpoint.name)
            task.intents.set(intents)
            task.preflights.set(preflights)
            task.producerResets.set(resets)
            task.producerPrepares.set(prepares)
            task.producerPaths.set(producerIdentities)
            task.operationIntents.set(
                java.util.Map.of(
                    "initialize",
                    operationIntents["initialize"],
                    "find",
                    operationIntents["find"]
                )
            )
            task.planRecord.set(reset.flatMap { value -> value.workspace.file("plan.json") })
            task.validationReceipt.set(reset.flatMap { value -> value.workspace.file("validated.json") })
            task.dependsOn(checkpoint)
        }
        val publicOperations: MutableList<TaskProvider<out DirectSession>> = ArrayList<TaskProvider<out DirectSession>>()
        operationPaths.entries.stream().filter { entry: MutableMap.MutableEntry<String, String> -> entry.key == "initialize" || entry.key == "find" }.forEach { entry: MutableMap.MutableEntry<String, String> ->
            val mode: String = entry.key
            val path: String = entry.value
            val operationType: Class<out DirectSession> = if (mode == "find") FindSession::class.java else DirectSession::class.java
            publicOperations.add(
                root.tasks.register(path.substring(1), operationType) { task ->
                    publishing(
                        task,
                        (if (mode == "find") "Finds a matching staging repository" else "Initializes a staging repository") + " in Nexus repository '" + route.getName() + "'."
                    )
                    policy(
                        task,
                        events,
                        reset,
                        route,
                        coordinator,
                        repository,
                        bootstrap.isSuppressIvyModuleMetadata()
                    )
                    task.mode.set(mode)
                    task.checkpoint.set(":" + validate.name)
                    task.operationPaths.set(operationPaths)
                    task.validatePath.set(":" + validate.name)
                    task.planRecord.set(validate.flatMap { obj: WorkflowValidate -> obj.planRecord })
                    task.validationReceipt.set(validate.flatMap { obj: WorkflowValidate -> obj.validationReceipt })
                    task.session.set(reset.flatMap { value -> value.workspace.file("$mode-session.json") })
                    task.dependsOn(operationIntents[mode])
                }
            )
        }
        val modeCheckpoint: TaskProvider<Task> = root.tasks.register(scope + "_modeCheckpoint") { task ->
            task.dependsOn(validate)
            task.mustRunAfter(publicOperations)
        }
        val session: TaskProvider<DirectSession> = root.tasks.register(scope + "_session", DirectSession::class.java) { task ->
            policy(
                task,
                events,
                reset,
                route,
                coordinator,
                repository,
                bootstrap.isSuppressIvyModuleMetadata()
            )
            task.mode.set("auto")
            task.checkpoint.set(":" + modeCheckpoint.name)
            task.operationPaths.set(operationPaths)
            task.dependsOn(modeCheckpoint)
            task.possibleReceipts.from(
                reset.flatMap { obj: WorkflowReset -> obj.workspace }.map { directory ->
                    directory.asFileTree.matching { patterns ->
                        patterns.include(
                            "initialize-session.json",
                            "find-session.json"
                        )
                    }
                }
            )
            task.validatePath.set(":" + validate.name)
            task.planRecord.set(validate.flatMap { obj: WorkflowValidate -> obj.planRecord })
            task.validationReceipt.set(validate.flatMap { obj: WorkflowValidate -> obj.validationReceipt })
            task.session.set(reset.flatMap { value -> value.workspace.file("session.json") })
        }
        val upload: TaskProvider<WorkflowUpload> = root.tasks.register(scope + "_upload", WorkflowUpload::class.java) { task ->
            policy(
                task,
                events,
                reset,
                route,
                coordinator,
                repository,
                bootstrap.isSuppressIvyModuleMetadata()
            )
            task.sessionPath.set(":" + session.name)
            task.planRecord.set(validate.flatMap { obj: WorkflowValidate -> obj.planRecord })
            task.session.set(session.flatMap { obj: DirectSession -> obj.session })
            task.completion.set(reset.flatMap { value -> value.workspace.file("upload.json") })
        }
        val beforeClose: TaskProvider<Task> = root.tasks.register(scope + "_beforeClose") { task ->
            task.dependsOn(reset)
            task.mustRunAfter(
                intentTasks,
                preflightTasks,
                operationIntentTasks,
                validate,
                publicOperations,
                session,
                upload
            )
        }
        val transitions: MutableMap<String, TaskProvider<WorkflowTransition>> = LinkedHashMap<String, TaskProvider<WorkflowTransition>>()
        for (operation in listOf("close", "release")) {
            val checkpointForTransition: TaskProvider<Task>
            if (operation == "close") {
                checkpointForTransition = beforeClose
            } else {
                checkpointForTransition = root.tasks.register(scope + "_beforeRelease") { task ->
                    task.dependsOn(reset)
                    task.mustRunAfter(
                        intentTasks,
                        preflightTasks,
                        operationIntentTasks,
                        validate,
                        publicOperations,
                        session,
                        upload,
                        transitions["close"]
                    )
                }
            }
            transitions[operation] =
                root.tasks.register(operationPaths.getValue(operation).substring(1), WorkflowTransition::class.java) { task ->
                    publishing(
                        task,
                        (if (operation == "close") "Closes" else "Releases") + " the selected staging repository in Nexus repository '" + route.getName() + "'."
                    )
                    policy(
                        task,
                        events,
                        reset,
                        route,
                        coordinator,
                        repository,
                        bootstrap.isSuppressIvyModuleMetadata()
                    )
                    task.operation.set(operation)
                    task.checkpoint.set(":" + checkpointForTransition.name)
                    task.validatePath.set(":" + validate.name)
                    task.sessionPath.set(":" + session.name)
                    task.uploadPath.set(":" + upload.name)
                    task.intents.set(intents)
                    task.operationIntents.set(operationIntents)
                    task.operationPaths.set(operationPaths)
                    task.maxRetries.set(coordinator.maxRetries)
                    task.retryDelay.set(coordinator.delayBetween.toMillis())
                    task.possibleReceipts.from(
                        reset.flatMap { obj: WorkflowReset -> obj.workspace }.map { directory ->
                            directory.asFileTree.matching { patterns ->
                                patterns.include(
                                    "*.json"
                                )
                            }
                        }
                    )
                    task.completion.set(reset.flatMap { value -> value.workspace.file("$operation.json") })
                    task.dependsOn(checkpointForTransition, operationIntents[operation])
                    task.onlyIf("Staging is enabled", WorkflowTransition.StagingEnabled())
                }
        }
        root.tasks.register("closeAndRelease" + capitalRepository + "StagingRepository") { task ->
            publishing(
                task,
                "Closes and releases the selected staging repository in Nexus repository '" + route.getName() + "'."
            )
            task.dependsOn(transitions.values)
        }
        val aliases: MutableList<String> = ArrayList<String>()
        for (index in bootstrap.getProjectPaths().indices) {
            val path: String = bootstrap.getProjectPaths()[index]
            val unit = "p$index"
            val alias: String = route.unitPath(index).substring(1)
            if (bootstrap.getExclusions().contains(path)) {
                root.tasks.register(alias) { task -> task.doLast { ignored -> ignored.logger.lifecycle("NEXUS-EXCLUDED $path $format") } }
            } else {
                aliases.add(":$alias")
                root.tasks.register(alias) { task -> task.dependsOn(intents[unit], preflights[unit], upload) }
            }
        }
        root.tasks.register(route.getAggregateAlias()) { task ->
            publishing(
                task,
                "Publishes all nonexcluded projects to Nexus repository '" + route.getName() + "'."
            )
            task.dependsOn(aliases)
        }
    }

    companion object {
        @JvmStatic
        private fun publishing(task: Task, description: String) {
            task.group = "publishing"
            task.description = description
        }

        @JvmStatic
        private fun policy(task: WorkflowPolicy, events: Provider<CompletionObserver>, reset: TaskProvider<WorkflowReset>, route: RepositoryRoute, coordinator: RootCoordinator, repository: RootCoordinator.Repository, suppressIvyModuleMetadata: Boolean) {
            task.observation.set(events)
            task.usesService(events)
            task.repositoryAlias.set(route.getName())
            task.format.set(route.format)
            task.explicitIvyLayout.set(repository.getSpec().explicitIvyLayout)
            task.m2Compatible.set(!repository.getSpec().explicitIvyLayout || repository.getSpec().m2Compatible)
            task.artifactPatterns.set(if (repository.getSpec().explicitIvyLayout) repository.getSpec().artifactPatterns else java.util.List.of(IvyArtifactRepository.MAVEN_ARTIFACT_PATTERN))
            task.ivyPatterns.set(if (repository.getSpec().explicitIvyLayout) repository.getSpec().ivyPatterns else java.util.List.of(IvyArtifactRepository.MAVEN_IVY_PATTERN))
            task.suppressIvyModuleMetadata.set(suppressIvyModuleMetadata)
            task.resetPath.set(route.resetPath)
            task.workspace.set(reset.flatMap { obj: WorkflowReset -> obj.workspace })
            task.staged.set(reset.flatMap { value -> value.workspace.dir("staged") })
            task.staging.set(coordinator.useStaging)
            task.insecure.set(repository.getSpec().allowInsecureProtocol)
            task.endpoint.set(repository.getSpec().snapshotRepositoryUrl)
            task.nexusEndpoint.set(repository.getSpec().nexusUrl)
            task.profile.set(repository.getSpec().stagingProfileId ?: "")
            task.resumeId.set(repository.getSpec().stagingRepositoryId ?: "")
            task.packageGroup.set(coordinator.packageGroup)
            task.repositoryDescription.set(coordinator.repositoryDescription)
            task.descriptionRegex.convention(
                coordinator.repositoryDescription.map { value ->
                    "\\b" + Pattern.quote(
                        value
                    ) + "(\\s|$)"
                }
            )
            task.username.set(repository.username)
            task.password.set(repository.password)
            task.connectTimeout.set(Math.toIntExact(coordinator.connectTimeout.toMillis()))
            task.requestTimeout.set(Math.toIntExact(coordinator.clientTimeout.toMillis()))
        }
    }
}
