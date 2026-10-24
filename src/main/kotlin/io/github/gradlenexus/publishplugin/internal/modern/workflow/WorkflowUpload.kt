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

import io.github.gradlenexus.publishplugin.internal.modern.transport.DeploymentEndpoint
import io.github.gradlenexus.publishplugin.internal.modern.transport.GradlePublicationTransport
import io.github.gradlenexus.publishplugin.tasks.NexusRemotePublish
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Path

@DisableCachingByDefault(because = "Current invocation action and admission must be fresh")
abstract class WorkflowUpload :
    WorkflowPolicy(),
    NexusRemotePublish {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val planRecord: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val session: RegularFileProperty

    @get:Input abstract val sessionPath: Property<String>

    @get:OutputFile abstract val completion: RegularFileProperty

    @TaskAction
    @Throws(Exception::class)
    fun upload() {
        val values: Map<String, CompletionObserver.Outcome> = observation(sessionPath.get())
        CompletionObserver.requireFresh(values, sessionPath.get())
        val file: Path = planRecord.get().asFile.toPath()
        val plan: WorkflowFiles.Record = plan(file)
        val session: WorkflowFiles.Record = sessionReceipt(session.get().asFile.toPath(), plan, file, "session")
        if (!plan.empty) {
            val destination: DeploymentEndpoint = DeploymentEndpoint.validate(session.destination!!, insecure.get())
            GradlePublicationTransport(services).publish(directories(plan), format.get(), destination, username.getOrNull(), password.getOrNull(), temporaryDir, artifactPatterns.get(), ivyPatterns.get(), m2Compatible.get())
        }
        session.operation = "upload"
        WorkflowFiles.write(completion.get().asFile.toPath(), session)
        logger.lifecycle("NEXUS-WORKFLOW-UPLOADED repository=" + plan.repository + " units=" + plan.units + " invocation=" + plan.invocation + " empty=" + plan.empty)
    }
}
