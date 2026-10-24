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

import io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec
import io.github.gradlenexus.publishplugin.tasks.NexusRepositorySession
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Path

@DisableCachingByDefault(because = "Current invocation action and admission must be fresh")
abstract class DirectSession :
    WorkflowPolicy(),
    NexusRepositorySession {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val planRecord: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val validationReceipt: RegularFileProperty

    @get:Input abstract val validatePath: Property<String>

    @get:Input abstract val mode: Property<String>

    @get:Input abstract val checkpoint: Property<String>

    @get:Input abstract val operationPaths: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val possibleReceipts: ConfigurableFileCollection

    @get:OutputFile abstract val session: RegularFileProperty

    @TaskAction
    @Throws(Exception::class)
    fun acquire() {
        val values: Map<String, CompletionObserver.Outcome> = observation(checkpoint.get())
        CompletionObserver.requireFresh(values, validatePath.get())
        val planFile: Path = planRecord.get().asFile.toPath()
        val validation: WorkflowFiles.Record = WorkflowFiles.readPhase(validationReceipt.get().asFile.toPath(), observation.get().getInvocation(), repositoryAlias.get(), "planBinding", "modes", "resumeId", "sessionPolicyIdentity", "suppressIvyModuleMetadata")
        if (!matchesFormatPolicy(validation) || ("validation" != validation.operation) || (BundleCodec.digest(planFile) != validation.planBinding)) throw GradleException("NEXUS_VALIDATED_PLAN_CHANGED")
        val plan: WorkflowFiles.Record = plan(planFile)
        val mode: String = mode.get()
        if (mode == "auto" && plan.modes.isNotEmpty()) {
            val source: String = plan.modes[0]
            CompletionObserver.requireFresh(values, operationPaths.get()[source])
            val receipt: WorkflowFiles.Record = sessionReceipt(workspace.get().asFile.toPath().resolve("$source-session.json"), plan, planFile, source)
            receipt.operation = "session"
            WorkflowFiles.write(session.get().asFile.toPath(), receipt)
            logger.lifecycle("NEXUS-WORKFLOW-ADOPTED repository=" + plan.repository + " id=" + receipt.repositoryId)
            return
        }
        if (mode != "auto" && !plan.modes.contains(mode)) throw GradleException("NEXUS_CONFLICTING_SESSION_POLICIES")
        var handle: RootSessionBackend.Handle? = null
        if (needsSession(plan)) {
            val request: RootSessionBackend.Request = RootSessionBackend.Request(endpoint().getUri(), mode, if (mode == "auto") plan.resumeId else "", profile.get(), packageGroup.get(), repositoryDescription.get(), descriptionRegex.get(), connectTimeout.get(), requestTimeout.get())
            handle = RootSessionBackend.forRoute(staging.get()).acquire(request, username.getOrNull(), password.getOrNull())
            RootSessionBackend.validateHandle(handle, staging.get(), endpoint().getUri(), plan.resumeId)
            if (handle.repositoryId.isNotEmpty()) logger.lifecycle("NEXUS-WORKFLOW-REPOSITORY repository=" + plan.repository + " id=" + handle.repositoryId)
        }
        plan.operation = if (mode == "auto") "session" else mode
        plan.planBinding = BundleCodec.digest(planFile)
        plan.backend = if (handle == null) "none" else handle.backend
        plan.destination = if (handle == null) "" else handle.destination.toASCIIString()
        plan.repositoryId = if (handle == null) "" else handle.repositoryId
        plan.queryIdentity = if (mode == "find") WorkflowFiles.identity(descriptionRegex.get()) else ""
        WorkflowFiles.write(session.get().asFile.toPath(), plan)
    }
}
