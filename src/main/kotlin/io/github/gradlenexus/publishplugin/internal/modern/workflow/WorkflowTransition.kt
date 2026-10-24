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

import io.github.gradlenexus.publishplugin.tasks.NexusStagingRepositoryTransition
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option
import org.gradle.work.DisableCachingByDefault
import java.io.Serializable
import java.nio.file.Path
import java.util.ArrayList

/**
 * Public operation owns its CLI option; observation and file data do not infer reverse task edges.
 */
@DisableCachingByDefault(because = "Remote transition and current action receipt must be fresh")
abstract class WorkflowTransition :
    WorkflowPolicy(),
    NexusStagingRepositoryTransition {
    @get:Input abstract val operation: Property<String>

    @get:Input abstract override val explicitRepositoryId: Property<String>

    @get:Input abstract val checkpoint: Property<String>

    @get:Input abstract val validatePath: Property<String>

    @get:Input abstract val sessionPath: Property<String>

    @get:Input abstract val uploadPath: Property<String>

    @get:Input abstract val intents: MapProperty<String, String>

    @get:Input abstract val operationIntents: MapProperty<String, String>

    @get:Input abstract val operationPaths: MapProperty<String, String>

    @get:Input abstract val maxRetries: Property<Int>

    @get:Input abstract val retryDelay: Property<Long>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val possibleReceipts: ConfigurableFileCollection

    @get:OutputFile abstract val completion: RegularFileProperty

    init {
        explicitRepositoryId.convention("")
    }

    @Option(option = "staging-repository-id", description = "Explicit staging repository ID for independent recovery")
    fun repositoryId(value: String) {
        explicitRepositoryId.set(value)
    }

    @TaskAction
    @Throws(Exception::class)
    fun transition() {
        val outcomes: Map<String, CompletionObserver.Outcome> = observation(checkpoint.get())
        var publication = false
        for (path in intents.get().values) {
            if (outcomes.containsKey(path)) {
                CompletionObserver.requireFresh(outcomes, path)
                publication = true
            }
        }
        val selected: MutableList<String> = ArrayList<String>()
        for ((key, value) in operationIntents.get()) {
            if (outcomes.containsKey(value)) {
                CompletionObserver.requireFresh(outcomes, value)
                selected.add(key)
            }
        }
        val workspace: Path = workspace.get().asFile.toPath()
        var id: String = explicitRepositoryId.get()
        if (id.isNotEmpty()) RootSessionBackend.identifier(id)
        val receipt: WorkflowFiles.Record
        if (publication || selected.contains("initialize") || selected.contains("find")) {
            CompletionObserver.requireFresh(outcomes, validatePath.get())
            val planFile: Path = workspace.resolve("plan.json")
            val plan: WorkflowFiles.Record = plan(planFile)
            if (publication) {
                CompletionObserver.requireFresh(outcomes, sessionPath.get())
                CompletionObserver.requireFresh(outcomes, uploadPath.get())
                receipt = sessionReceipt(workspace.resolve("session.json"), plan, planFile, "session")
                val uploaded: WorkflowFiles.Record = sessionReceipt(workspace.resolve("upload.json"), plan, planFile, "upload")
                if (receipt.repositoryId != uploaded.repositoryId) throw GradleException("NEXUS_WORKFLOW_SESSION_MISMATCH")
            } else {
                val mode: String = plan.modes[0]
                CompletionObserver.requireFresh(outcomes, operationPaths.get()[mode])
                receipt = sessionReceipt(workspace.resolve("$mode-session.json"), plan, planFile, mode)
            }
            if (id.isNotEmpty() && id != receipt.repositoryId) throw GradleException("NEXUS_CONFLICTING_RECOVERY_ID")
            id = receipt.repositoryId!!
        } else {
            if (id.isEmpty()) throw GradleException("NEXUS_EXPLICIT_STAGING_ID_REQUIRED")
            receipt = WorkflowFiles.Record()
            receipt.invocation = observation.get().getInvocation()
            receipt.repository = repositoryAlias.get()
            formatPolicy(receipt)
            receipt.route = "nexus2"
            receipt.endpointIdentity = endpointIdentity()
            receipt.planBinding = ""
            receipt.repositoryId = id
            receipt.backend = "nexus2"
        }
        if (operation.get().equals("release") && selected.contains("close")) {
            CompletionObserver.requireFresh(outcomes, operationPaths.get()["close"])
            val closed: WorkflowFiles.Record = WorkflowFiles.readPhase(workspace.resolve("close.json"), receipt.invocation, receipt.repository, "repositoryId", "planBinding", "suppressIvyModuleMetadata")
            if (!matchesFormatPolicy(closed) || ("close" != closed.operation) || (id != closed.repositoryId) || (receipt.planBinding != closed.planBinding) || (receipt.endpointIdentity != closed.endpointIdentity)) throw GradleException("NEXUS_CONFLICTING_RECOVERY_ID")
        }
        if (id.isNotEmpty()) {
            logger.lifecycle("NEXUS-WORKFLOW-RECOVERY repository=" + receipt.repository + " id=" + id)
            Nexus2Transitions.transition(
                endpoint().getUri(), operation.get(), id, repositoryDescription.get(), username.getOrNull(), password.getOrNull(),
                connectTimeout.get(), requestTimeout.get(), maxRetries.get(), retryDelay.get()
            )
        }
        receipt.operation = operation.get()
        receipt.repositoryId = id
        WorkflowFiles.write(completion.get().asFile.toPath(), receipt)
        logger.lifecycle("NEXUS-WORKFLOW-TRANSITION repository=" + receipt.repository + " operation=" + receipt.operation + " id=" + id)
    }

    class StagingEnabled :
        Spec<Task>,
        Serializable {
        override fun isSatisfiedBy(task: Task): Boolean =
            (task as WorkflowTransition).staging.get()
    }
}
