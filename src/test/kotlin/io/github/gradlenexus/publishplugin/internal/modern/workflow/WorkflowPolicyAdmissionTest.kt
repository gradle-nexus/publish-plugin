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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledForJreRange
import org.junit.jupiter.api.condition.JRE
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

/** Actual admission helpers only; no task action, Settings application or CC/IP claim. */
@EnabledForJreRange(min = JRE.JAVA_17)
class WorkflowPolicyAdmissionTest {
    @TempDir
    lateinit var directory: Path
    private lateinit var policy: AdmissionProbe
    private lateinit var plan: WorkflowFiles.Record
    private lateinit var planFile: Path
    private lateinit var receiptFile: Path

    @BeforeEach
    fun configure() {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        policy = project.tasks.register("admissionProbe", AdmissionProbe::class.java).get()
        policy.format.set("maven")
        policy.suppressIvyModuleMetadata.set(false)
        policy.staging.set(true)
        policy.insecure.set(false)
        policy.nexusEndpoint.set("https://repository.invalid/service/local/")
        policy.resumeId.set("")
        policy.profile.set("profile")
        policy.packageGroup.set("group")
        policy.repositoryDescription.set("description")
        policy.repositoryAlias.set("probe")
        policy.workspace.set(directory.toFile())
        policy.staged.set(Files.createDirectory(directory.resolve("staged")).toFile())
        policy.observation.set(project.gradle.sharedServices.registerIfAbsent("admissionObserver", CompletionObserver::class.java) { })
        plan = record()
        planFile = directory.resolve("plan.json")
        receiptFile = directory.resolve("session.json")
        Files.write(directory.resolve("invocation.txt"), plan.invocation!!.toByteArray())
        WorkflowFiles.write(planFile, plan)
    }

    @Test
    fun `current session receipt is admitted and stale invocation is rejected`() {
        val receipt = receipt()
        writeReceipt(receipt)
        assertThat(policy.readSession(receiptFile, plan, planFile).repositoryId).isEqualTo("repo-1")
        receipt.invocation = "previous"
        writeReceipt(receipt)
        assertThatThrownBy { policy.readSession(receiptFile, plan, planFile) }
            .isInstanceOf(IllegalStateException::class.java).hasMessage("NEXUS_WORKFLOW_INVALID_CURRENT_RECEIPT")
    }

    @ParameterizedTest
    @ValueSource(strings = ["operation", "planBinding", "units", "modes", "resumeId", "empty", "route", "format", "layoutPolicy", "endpointIdentity", "sessionPolicyIdentity", "suppressIvyModuleMetadata"])
    fun `each session binding mismatch rejects admission`(field: String) {
        val receipt = receipt()
        when (field) {
            "operation" -> receipt.operation = "other"
            "planBinding" -> receipt.planBinding = "other"
            "units" -> receipt.units = mutableListOf("p9")
            "modes" -> receipt.modes = mutableListOf("find")
            "resumeId" -> receipt.resumeId = "other"
            "empty" -> receipt.empty = true
            "route" -> receipt.route = "direct"
            "format" -> receipt.format = "ivy"
            "layoutPolicy" -> receipt.layoutPolicy = "other"
            "endpointIdentity" -> receipt.endpointIdentity = "other"
            "sessionPolicyIdentity" -> receipt.sessionPolicyIdentity = "other"
            "suppressIvyModuleMetadata" -> receipt.suppressIvyModuleMetadata = true
        }
        writeReceipt(receipt)
        rejectedSession()
    }

    @ParameterizedTest
    @ValueSource(strings = ["backend", "destination", "repositoryId", "queryIdentity"])
    fun `find handle and query identity must match the validated session`(field: String) {
        plan.modes.add("find")
        val receipt = receipt()
        receipt.queryIdentity = "a".repeat(64)
        writeReceipt(receipt)
        assertThat(policy.readSession(receiptFile, plan, planFile).queryIdentity).isEqualTo("a".repeat(64))
        when (field) {
            "backend" -> receipt.backend = "direct"
            "destination" -> receipt.destination = "https://other.invalid/"
            "repositoryId" -> receipt.repositoryId = "bad/id"
            "queryIdentity" -> receipt.queryIdentity = "wrong"
        }
        writeReceipt(receipt)
        rejectedSession()
    }

    @Test
    fun `format mismatch short circuits before reading a missing plan file`() {
        val receipt = receipt().apply { format = "ivy" }
        writeReceipt(receipt)
        Files.delete(planFile)
        rejectedSession()
    }

    @Test
    fun `empty automatic session requires none handle without resolving an endpoint`() {
        plan.empty = true
        val receipt = receipt().apply {
            backend = "none"
            destination = ""
            repositoryId = ""
        }
        policy.nexusEndpoint.unset()
        writeReceipt(receipt)
        assertThat(policy.readSession(receiptFile, plan, planFile).backend).isEqualTo("none")
        listOf("backend", "destination", "repositoryId").forEach { field ->
            val changed = receipt().apply {
                backend = "none"
                destination = ""
                repositoryId = ""
            }
            when (field) {
                "backend" -> changed.backend = "nexus2"
                "destination" -> changed.destination = "https://other.invalid/"
                "repositoryId" -> changed.repositoryId = "repo-1"
            }
            writeReceipt(changed)
            rejectedSession()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["valid", "resumeId", "sessionPolicyIdentity", "route", "empty", "operation", "fingerprints"])
    fun `validated plan compares policy identity emptiness operation and fingerprints`(field: String) {
        plan.empty = true
        plan.units.clear()
        when (field) {
            "resumeId" -> plan.resumeId = "other"
            "sessionPolicyIdentity" -> plan.sessionPolicyIdentity = "other"
            "route" -> plan.route = "direct"
            "empty" -> plan.empty = false
            "operation" -> plan.operation = "other"
            "fingerprints" -> plan.fingerprints = mapOf("changed" to "other")
        }
        WorkflowFiles.write(planFile, plan)
        if (field == "valid") {
            policy.nexusEndpoint.unset()
            assertThat(policy.readPlan(planFile).empty).isTrue()
        } else {
            assertThatThrownBy { policy.readPlan(planFile) }.isInstanceOf(GradleException::class.java).hasMessage("NEXUS_VALIDATED_PLAN_CHANGED")
        }
    }

    private fun record(): WorkflowFiles.Record =
        WorkflowFiles.Record().apply {
            invocation = policy.observation.get().getInvocation()
            repository = "probe"
            operation = "plan"
            units = mutableListOf("p0")
            format = "maven"
            route = "nexus2"
            layoutPolicy = "maven-standard"
            endpointIdentity = "endpoint"
            suppressIvyModuleMetadata = false
            sessionPolicyIdentity = WorkflowFiles.identity("true", "[]", "", "profile", "group", "description")
        }

    private fun receipt(): WorkflowFiles.Record =
        record().apply {
            units = plan.units.toMutableList()
            modes = plan.modes.toMutableList()
            empty = plan.empty
            operation = "session"
            backend = "nexus2"
            repositoryId = "repo-1"
            destination = "https://repository.invalid/service/local/staging/deployByRepositoryId/repo-1/"
            planBinding = BundleCodec.digest(planFile)
        }

    private fun writeReceipt(receipt: WorkflowFiles.Record) =
        WorkflowFiles.write(receiptFile, receipt)

    private fun rejectedSession() {
        assertThatThrownBy { policy.readSession(receiptFile, plan, planFile) }
            .isInstanceOf(GradleException::class.java).hasMessage("NEXUS_WORKFLOW_SESSION_MISMATCH")
    }

    abstract class AdmissionProbe : WorkflowPolicy() {
        fun readSession(file: Path, plan: WorkflowFiles.Record, planFile: Path): WorkflowFiles.Record =
            sessionReceipt(file, plan, planFile, "session")

        fun readPlan(file: Path): WorkflowFiles.Record =
            plan(file)
    }
}
