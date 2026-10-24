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
import io.github.gradlenexus.publishplugin.internal.modern.bundle.NativeTaskNames
import io.github.gradlenexus.publishplugin.internal.modern.transport.GradlePublicationTransport
import io.github.gradlenexus.publishplugin.internal.modern.transport.IvyPublicationPlan
import io.github.gradlenexus.publishplugin.internal.modern.transport.MavenPublicationPlan
import io.github.gradlenexus.publishplugin.internal.modern.transport.NativeIvyPaths
import io.github.gradlenexus.publishplugin.internal.modern.transport.PublicationDescription
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Current invocation action and admission must be fresh")
abstract class WorkflowValidate : WorkflowPolicy() {
    @get:Input abstract val checkpoint: Property<String>

    @get:Input abstract val intents: MapProperty<String, String>

    @get:Input abstract val preflights: MapProperty<String, String>

    @get:Input abstract val producerResets: MapProperty<String, String>

    @get:Input abstract val producerPrepares: MapProperty<String, String>

    @get:Input abstract val producerPaths: MapProperty<String, String>

    @get:Input abstract val operationIntents: MapProperty<String, String>

    @get:OutputFile abstract val planRecord: RegularFileProperty

    @get:OutputFile abstract val validationReceipt: RegularFileProperty

    @Throws(Exception::class)
    private fun requireNativeExports(outcomes: Map<String, CompletionObserver.Outcome>, unit: String, model: Map<String, Any>) {
        val producerPath: String = producerPaths.get().getValue(unit)
        if (producerPath != model["producerPath"] || !format.get().equals(model["format"])) throw GradleException("NEXUS_PRODUCER_BUNDLE_IDENTITY_MISMATCH")
        for (publication in model["publications"] as List<Map<String, Any>>) {
            CompletionObserver.requireFresh(outcomes, NativeTaskNames.exportPath(producerPath, format.get(), publication["name"].toString()))
        }
    }

    @TaskAction
    @Throws(Exception::class)
    fun validate() {
        val values: Map<String, CompletionObserver.Outcome> = observation(checkpoint.get())
        val plan: WorkflowFiles.Record = WorkflowFiles.Record()
        formatPolicy(plan)
        plan.route = if (staging.get()) "nexus2" else "direct"
        plan.invocation = observation.get().getInvocation()
        plan.repository = repositoryAlias.get()
        plan.operation = "plan"
        val models = linkedMapOf<String, Map<String, Any>>()
        for ((unit, value) in intents.get()) {
            if (!values.containsKey(value)) continue
            CompletionObserver.requireFresh(values, value)
            CompletionObserver.requireFresh(values, preflights.get()[unit])
            CompletionObserver.requireFresh(values, producerResets.get()[unit])
            CompletionObserver.requireFresh(values, producerPrepares.get()[unit])
            val model = BundleCodec.readModel(staged.get().asFile.toPath().resolve(unit))
            requireNativeExports(values, unit, model)
            models[unit] = model
            plan.units.add(unit)
        }
        plan.units.sort()
        for ((key, value) in operationIntents.get()) {
            if (!values.containsKey(value)) continue
            CompletionObserver.requireFresh(values, value)
            plan.modes.add(key)
        }
        plan.modes.sort()
        plan.resumeId = resumeId.get()
        RootSessionBackend.validatePolicy(staging.get(), plan.modes, plan.resumeId)
        plan.sessionPolicyIdentity = sessionPolicyIdentity(plan.modes)
        val publications = plan.units.flatMap { unit ->
            PublicationDescription.fromModel(staged.get().asFile.toPath().resolve(unit), format.get(), models.getValue(unit))
        }
        if (format.get() == "maven") {
            val transportPlan = MavenPublicationPlan.planPublications(publications)
            plan.empty = transportPlan.isEmpty()
            if (!plan.empty) GradlePublicationTransport(services).validate(transportPlan)
        } else {
            if (format.get() != "ivy") throw GradleException("NEXUS_UNSUPPORTED_PUBLICATION_FORMAT")
            val transportPlan = IvyPublicationPlan.planPublications(publications, artifactPatterns.get(), ivyPatterns.get(), m2Compatible.get(), suppressIvyModuleMetadata.get(), NativeIvyPaths)
            plan.empty = transportPlan.getPayloads().isEmpty()
            if (!plan.empty) GradlePublicationTransport(services).validate(transportPlan)
        }
        if (needsSession(plan)) {
            if (staging.get() && profile.get().isNotEmpty()) RootSessionBackend.identifier(profile.get())
            if ((username.getOrNull() == null) != (password.getOrNull() == null)) throw GradleException("NEXUS_INCOMPLETE_CREDENTIALS")
        }
        plan.endpointIdentity = if (needsSession(plan)) endpointIdentity() else ""
        plan.fingerprints = WorkflowFiles.fingerprints(staged.get().asFile.toPath(), plan.units)
        WorkflowFiles.write(planRecord.get().asFile.toPath(), plan)
        plan.operation = "validation"
        plan.planBinding = BundleCodec.digest(planRecord.get().asFile.toPath())
        WorkflowFiles.write(validationReceipt.get().asFile.toPath(), plan)
        logger.lifecycle("NEXUS-WORKFLOW-VALIDATED repository=" + plan.repository + " units=" + plan.units + " invocation=" + plan.invocation)
    }
}
