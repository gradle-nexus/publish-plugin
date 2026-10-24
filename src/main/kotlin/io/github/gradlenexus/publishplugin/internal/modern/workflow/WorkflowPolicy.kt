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
import io.github.gradlenexus.publishplugin.internal.modern.transport.DeploymentEndpoint
import io.github.gradlenexus.publishplugin.internal.modern.transport.IvyPublicationPlan
import io.github.gradlenexus.publishplugin.internal.modern.transport.MavenPublicationPlan
import io.github.gradlenexus.publishplugin.internal.modern.transport.NativeIvyPaths
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.work.DisableCachingByDefault
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ArrayList
import java.util.HexFormat

@DisableCachingByDefault(because = "Current invocation remote policy and action receipts must be fresh")
abstract class WorkflowPolicy : WorkflowTask() {
    @get:Input abstract val repositoryAlias: Property<String>

    @get:Input abstract val format: Property<String>

    @get:Input abstract val explicitIvyLayout: Property<Boolean>

    @get:Input abstract val m2Compatible: Property<Boolean>

    @get:Input abstract val artifactPatterns: ListProperty<String>

    @get:Input abstract val ivyPatterns: ListProperty<String>

    @get:Input abstract val suppressIvyModuleMetadata: Property<Boolean>

    @get:Input abstract val resetPath: Property<String>

    @get:Input abstract val staging: Property<Boolean>

    @get:Input abstract val insecure: Property<Boolean>

    @get:Input abstract val connectTimeout: Property<Int>

    @get:Input abstract val requestTimeout: Property<Int>

    @get:Internal abstract val endpoint: Property<String>

    @get:Internal abstract val nexusEndpoint: Property<String>

    @get:Internal abstract val profile: Property<String>

    @get:Internal abstract val resumeId: Property<String>

    @get:Internal abstract val packageGroup: Property<String>

    @get:Internal abstract val repositoryDescription: Property<String>

    @get:Input abstract val descriptionRegex: Property<String>

    @get:Internal abstract val username: Property<String>

    @get:Internal abstract val password: Property<String>

    @get:Internal abstract val workspace: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val staged: DirectoryProperty

    @Throws(Exception::class)
    protected fun observation(checkpoint: String): Map<String, CompletionObserver.Outcome> {
        val values: Map<String, CompletionObserver.Outcome> = observation.get().await(checkpoint)
        CompletionObserver.requireFresh(values, resetPath.get())
        WorkflowFiles.requireToken(workspace.get().asFile.toPath(), observation.get().getInvocation())
        return values
    }

    protected fun endpoint(): DeploymentEndpoint {
        val value: String? = (if (staging.get()) nexusEndpoint else endpoint).getOrNull()
        if (value == null) throw GradleException("NEXUS_REPOSITORY_ENDPOINT_REQUIRED")
        return DeploymentEndpoint.validate(value, insecure.get())
    }

    @Throws(Exception::class)
    protected fun endpointIdentity(): String {
        val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(endpoint().getUri().toASCIIString().toByteArray(StandardCharsets.UTF_8))
        return HexFormat.of().formatHex(hash)
    }

    @Throws(Exception::class)
    protected fun plan(file: Path): WorkflowFiles.Record {
        WorkflowFiles.requireToken(workspace.get().asFile.toPath(), observation.get().getInvocation())
        val plan: WorkflowFiles.Record = WorkflowFiles.readPhase(file, observation.get().getInvocation(), repositoryAlias.get(), "modes", "resumeId", "sessionPolicyIdentity", "suppressIvyModuleMetadata")
        if (!matchesFormatPolicy(plan)) throw GradleException("NEXUS_VALIDATED_PLAN_CHANGED")
        val empty: Boolean = transportEmpty(directories(plan))
        RootSessionBackend.validatePolicy(staging.get(), plan.modes, plan.resumeId)
        val matchesPlan = resumeId.get() == plan.resumeId &&
            sessionPolicyIdentity(plan.modes) == plan.sessionPolicyIdentity &&
            (if (staging.get()) "nexus2" else "direct") == plan.route &&
            empty == plan.empty &&
            plan.operation == "plan" &&
            plan.fingerprints == WorkflowFiles.fingerprints(staged.get().asFile.toPath(), plan.units) &&
            (!needsSession(plan) || endpointIdentity() == plan.endpointIdentity)
        if (!matchesPlan) throw GradleException("NEXUS_VALIDATED_PLAN_CHANGED")
        return plan
    }

    @Throws(Exception::class)
    protected fun layoutIdentity(): String {
        if (format.get().equals("maven")) return "maven-standard"
        if (!format.get().equals("ivy")) throw GradleException("NEXUS_UNSUPPORTED_PUBLICATION_FORMAT")
        val values: MutableList<String> = ArrayList<String>()
        values.add("ivy")
        values.add(if (explicitIvyLayout.get()) "explicit-pattern" else "default-maven")
        values.add(m2Compatible.get().toString())
        values.add(artifactPatterns.get().size.toString())
        values.addAll(artifactPatterns.get())
        values.add(ivyPatterns.get().size.toString())
        values.addAll(ivyPatterns.get())
        return "ivy:" + WorkflowFiles.identity(*values.toTypedArray())
    }

    @Throws(Exception::class)
    protected fun formatPolicy(record: WorkflowFiles.Record) {
        record.format = format.get()
        record.layoutPolicy = layoutIdentity()
        record.suppressIvyModuleMetadata = suppressIvyModuleMetadata.get()
    }

    @Throws(Exception::class)
    protected fun matchesFormatPolicy(record: WorkflowFiles.Record): Boolean =
        format.get().equals(record.format) && layoutIdentity() == record.layoutPolicy &&
            (suppressIvyModuleMetadata.get() == record.suppressIvyModuleMetadata)

    @Throws(Exception::class)
    private fun ivyPlan(directories: List<Path>): IvyPublicationPlan.Plan =
        IvyPublicationPlan.plan(directories, artifactPatterns.get(), ivyPatterns.get(), m2Compatible.get(), suppressIvyModuleMetadata.get(), NativeIvyPaths)

    @Throws(Exception::class)
    protected fun transportEmpty(directories: List<Path>): Boolean {
        if (format.get().equals("maven")) return MavenPublicationPlan.plan(directories).isEmpty()
        if (format.get().equals("ivy")) return ivyPlan(directories).getPayloads().isEmpty()
        throw GradleException("NEXUS_UNSUPPORTED_PUBLICATION_FORMAT")
    }

    @Throws(Exception::class)
    protected fun sessionPolicyIdentity(modes: List<String>): String =
        WorkflowFiles.identity(staging.get().toString(), modes.toString(), resumeId.get(), profile.get(), packageGroup.get(), repositoryDescription.get())

    protected fun needsSession(plan: WorkflowFiles.Record): Boolean =
        !plan.empty || plan.modes.isNotEmpty() || plan.resumeId.isNotEmpty()

    @Throws(Exception::class)
    protected fun sessionReceipt(file: Path, plan: WorkflowFiles.Record, planFile: Path, operation: String): WorkflowFiles.Record {
        val receipt: WorkflowFiles.Record = WorkflowFiles.readPhase(file, plan.invocation, plan.repository, "modes", "resumeId", "sessionPolicyIdentity", "backend", "repositoryId", "destination", "planBinding", "queryIdentity", "suppressIvyModuleMetadata")
        val matchesSession = matchesFormatPolicy(receipt) &&
            operation == receipt.operation &&
            BundleCodec.digest(planFile) == receipt.planBinding &&
            plan.units == receipt.units &&
            plan.modes == receipt.modes &&
            plan.resumeId == receipt.resumeId &&
            plan.empty == receipt.empty &&
            plan.route == receipt.route &&
            plan.format == receipt.format &&
            plan.layoutPolicy == receipt.layoutPolicy &&
            plan.endpointIdentity == receipt.endpointIdentity &&
            plan.sessionPolicyIdentity == receipt.sessionPolicyIdentity
        if (!matchesSession) throw GradleException("NEXUS_WORKFLOW_SESSION_MISMATCH")
        if (needsSession(plan)) {
            try {
                RootSessionBackend.validateHandle(RootSessionBackend.Handle(receipt.backend!!, URI.create(receipt.destination!!), receipt.repositoryId!!), staging.get(), endpoint().getUri(), plan.resumeId)
                if (plan.modes.contains("find")) {
                    val queryIdentity = receipt.queryIdentity
                    require(queryIdentity != null && queryIdentity.matches("[a-f0-9]{64}".toRegex()))
                }
            } catch (_: RuntimeException) {
                throw GradleException("NEXUS_WORKFLOW_SESSION_MISMATCH")
            }
        } else if (receipt.backend != "none" || receipt.destination != "" || receipt.repositoryId != "") {
            throw GradleException("NEXUS_WORKFLOW_SESSION_MISMATCH")
        }
        return receipt
    }

    protected fun directories(plan: WorkflowFiles.Record): List<Path> =
        plan.units.stream().map { unit: String -> staged.get().asFile.toPath().resolve(unit) }.toList()
}
