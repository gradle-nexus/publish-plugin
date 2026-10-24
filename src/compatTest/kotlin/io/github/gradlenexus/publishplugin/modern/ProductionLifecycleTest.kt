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

package io.github.gradlenexus.publishplugin.modern

import com.google.gson.Gson
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Actual packaged lifecycle operators, owned root receipts and controlled Nexus2 protocol. */
internal class ProductionLifecycleTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    @Tag("settings-smoke")
    fun `selected union uploads closes and releases once with fresh receipts on cache hit`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model)
            var before = 0
            val invocations = mutableSetOf<String>()
            ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publishToProbe", ":other:publishToProbe", ":closeAndReleaseProbeStagingRepository", ":closeAndReleaseStagingRepositories") { result ->
                val delta = model.server.requests.drop(before)
                assertThat(delta.filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/profiles/profile/start", "/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                assertThat(delta.filter { it.first == "PUT" }).isNotEmpty()
                assertThat(delta.filter { it.first == "PUT" }.map { it.second }).doesNotHaveDuplicates()
                val closeIndex = delta.indexOfFirst { it.first == "POST" && it.second.endsWith("/bulk/close") }
                assertThat(closeIndex).isGreaterThan(delta.indexOfLast { it.first == "PUT" })
                val id = "created-${model.created.get()}"
                assertThat(delta.filter { it.first == "GET" && it.second == "/service/local/staging/repository/$id" }).hasSize(2)
                listOf("child", "other").forEach { name ->
                    assertThat(model.server.files["/service/local/staging/deployByRepositoryId/$id/probe/$name/1.0/$name-1.0.jar"]).isEqualTo(File(project, "$name/build/libs/$name-1.0.jar").readBytes())
                }
                val workspace = workspace(project)
                val record = Gson().fromJson(File(workspace, "release.json").readText(), Map::class.java)
                assertThat(invocations.add(record["invocation"].toString())).isTrue()
                assertThat(record["repositoryId"]).isEqualTo(id)
                assertThat(result.task(":closeProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                assertThat(result.task(":releaseProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `rejected native upload retains fresh recovery ID and never admits close or release`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model)
            val tasks = arrayOf(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
            val build = ModernGradleBuild(project, false, runtime)
            build.storeAndReuse(*tasks)
            val normal = model.server.response
            model.server.response = { method, path ->
                if (method == "PUT") MemoryRepository.Response(403) else normal?.invoke(method, path)
            }
            repeat(2) {
                val before = model.server.requests.size
                val result = build.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL", *tasks)
                assertThat(result.output).contains("Configuration cache entry reused.", "403")
                val requests = model.server.requests.drop(before)
                assertThat(requests.filter { it.first == "POST" }.map { it.second })
                    .containsExactly("/service/local/staging/profiles/profile/start")
                assertThat(requests.filter { it.first == "PUT" }).hasSize(1)
                val session = Gson().fromJson(File(workspace(project), "session.json").readText(), Map::class.java)
                assertThat(session["repositoryId"]).isEqualTo("created-${model.created.get()}")
                assertThat(File(workspace(project), "upload.json")).doesNotExist()
                assertThat(File(workspace(project), "release.json")).doesNotExist()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    @Tag("settings-smoke")
    fun `later selected collision rejects the complete union before any acquisition`(runtime: ModernRuntime) {
        unionAdmission(runtime, listOf("collision"))
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `later publication collision or invalid identity blocks the complete union before staging`(runtime: ModernRuntime) {
        unionAdmission(runtime, listOf("invalid-identity"))
    }

    private fun unionAdmission(runtime: ModernRuntime, modes: List<String>) {
        modes.forEach { mode ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model)
                File(project, "other/build.gradle").appendText(
                    """
                    afterEvaluate {
                        publishing.publications.create('later', MavenPublication) {
                            groupId = 'probe'; artifactId = '${if (mode == "collision") "child" else "invalid-identity"}'; version = '1.0'
                            ${if (mode == "invalid-identity") "artifact(tasks.named('jar'))" else ""}
                        }
                    }
                    """.trimIndent() + "\n"
                )
                if (mode == "invalid-identity") {
                    File(project, "other/build.gradle").appendText(
                        """
                    tasks.named('prepareNexusMavenBundle') {
                        def manifest = layout.buildDirectory.file('nexus-publishing/bundles/maven/manifest.json')
                        doLast {
                            def file = manifest.get().asFile
                            def model = new groovy.json.JsonSlurper().parse(file)
                            model.publications.find { it.name == 'later' }.entries.find { it.role == 'artifact' }.name = 'wrong-name'
                            file.text = groovy.json.JsonOutput.toJson(model)
                        }
                    }
                        """.trimIndent() + "\n"
                    )
                }
                val build = ModernGradleBuild(project, false, runtime)
                repeat(2) { index ->
                    val result = build.failContinuingWithDiagnostic(
                        if (mode == "collision") "NEXUS_DESTINATION_COLLISION" else "NEXUS_MAVEN_IDENTITY_MAPPING",
                        ":child:publishToProbe",
                        ":other:publishToProbe",
                        ":closeAndReleaseProbeStagingRepository"
                    )
                    cache(result, index)
                    assertThat(result.task(":child:prepareNexusMavenBundle")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                    assertThat(result.task(":other:prepareNexusMavenBundle")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                    assertThat(model.server.requests).isEmpty()
                    assertThat(model.created.get()).isZero()
                    listOf("session.json", "upload.json", "close.json", "release.json").forEach { receipt ->
                        assertThat(File(workspace(project), receipt)).doesNotExist()
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `actual public options recover without a plan and conflicting IDs block release`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model)
            val build = ModernGradleBuild(project, false, runtime)
            var before = 0
            repeat(2) { index ->
                model.seed("recover")
                val result = build.runRecovery(StagingRecoveryTask(":closeProbeStagingRepository", "recover"), StagingRecoveryTask(":releaseProbeStagingRepository", "recover"))
                cache(result, index)
                assertThat(result.tasks.none { it.path.startsWith(":child:") || it.path.startsWith(":other:") || listOf("_validate", "_session", "_upload").any { suffix -> it.path.endsWith(suffix) } }).isTrue()
                assertThat(File(workspace(project), "plan.json")).doesNotExist()
                assertThat(model.server.requests.drop(before).filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                before = model.server.requests.size
            }
            repeat(2) { index ->
                model.seed("close-id")
                model.seed("release-id", "closed")
                val result = build.failRecoveryContinuingWithDiagnostic("NEXUS_CONFLICTING_RECOVERY_ID", StagingRecoveryTask(":closeProbeStagingRepository", "close-id"), StagingRecoveryTask(":releaseProbeStagingRepository", "release-id"))
                cache(result, index)
                assertThat(model.server.requests.drop(before).filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/bulk/close")
                assertThat(File(workspace(project), "release.json")).doesNotExist()
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `direct publication skips ordinary combined lifecycle even for release coordinates`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model)
            File(project, "settings.gradle").appendText("nexusPublishing.useStaging.set(false)\n")
            var before = 0
            ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publishToProbe", ":closeAndReleaseStagingRepositories") { result ->
                assertThat(result.task(":closeProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SKIPPED)
                assertThat(result.task(":releaseProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SKIPPED)
                val delta = model.server.requests.drop(before)
                assertThat(delta.none { it.first == "POST" || it.second.startsWith("/service/local/") }).isTrue()
                assertThat(delta.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/direct/") }
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `poll identity unknown states and lost close responses cannot admit promote`(runtime: ModernRuntime) {
        listOf("wrong-id", "unknown-transitioning", "disconnect", "removal-prefix").forEach { mode ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model)
                model.failure = mode
                val diagnostic = when (mode) {
                    "wrong-id" -> "NEXUS_TRANSITION_WRONG_REPOSITORY"
                    "unknown-transitioning" -> "NEXUS_TRANSITION_WRONG_STATE"
                    "disconnect" -> "NEXUS_TRANSITION_REQUEST_FAILED"
                    else -> "NEXUS_TRANSITION_POLL_HTTP_FAILURE status=404"
                }
                val build = ModernGradleBuild(project, false, runtime)
                var before = 0
                repeat(2) { index ->
                    model.seed("repo1")
                    val requests = if (mode == "removal-prefix") arrayOf(StagingRecoveryTask(":releaseProbeStagingRepository", "repo1")) else arrayOf(StagingRecoveryTask(":closeProbeStagingRepository", "repo1"), StagingRecoveryTask(":releaseProbeStagingRepository", "repo1"))
                    if (mode == "removal-prefix") model.seed("repo1", "closed")
                    val result = build.failRecoveryContinuingWithDiagnostic(diagnostic, *requests)
                    cache(result, index)
                    assertThat(model.server.requests.drop(before).filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/bulk/" + if (mode == "removal-prefix") "promote" else "close")
                    assertThat(File(workspace(project), "release.json")).doesNotExist()
                    if (mode != "removal-prefix") assertThat(File(workspace(project), "close.json")).doesNotExist()
                    before = model.server.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `invalid transition retry policy fails before a transition request`(runtime: ModernRuntime) {
        listOf("maxRetries.set(-1)", "delayBetween.set(java.time.Duration.ofMillis(-1))").forEach { policy ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model)
                File(project, "settings.gradle").appendText("nexusPublishing.transitionCheckOptions.$policy\n")
                model.seed("recover")
                val build = ModernGradleBuild(project, false, runtime)
                repeat(2) { index ->
                    val result = build.failRecoveryContinuingWithDiagnostic("NEXUS_INVALID_TRANSITION_POLICY", StagingRecoveryTask(":closeProbeStagingRepository", "recover"))
                    cache(result, index)
                    assertThat(model.server.requests).isEmpty()
                    assertThat(File(workspace(project), "close.json")).doesNotExist()
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `warm failed skipped and input invalid remote work never admits later transitions`(runtime: ModernRuntime) {
        data class Case(val operation: String, val failure: String, val project: File, val model: NexusModel)
        val models = mutableListOf<NexusModel>()
        try {
            val cases = listOf("session", "upload", "close").flatMap { operation ->
                listOf("disabled", "only-if", "input", "before", "after", "prerequisite").map { failure ->
                    val project = directory()
                    val model = NexusModel(project).also { models.add(it) }
                    configure(project, model)
                    Case(operation, failure, project, model)
                }
            }
            val builds = IndependentGradleBuilds(cases.map { it.project }, runtime)
            val requests = cases.associate { it.project to listOf(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository") }
            repeat(2) { index ->
                builds.run(requests).forEach { (project, result) ->
                    cache(result, index)
                    assertThat(result.tasks).noneMatch { it.outcome == TaskOutcome.FAILED }
                    assertThat(result.task(":releaseProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                    assertThat(File(workspace(project), "release.json")).exists()
                }
            }
            cases.forEach { (operation, failure, project, _) ->
                val target = when (operation) {
                    "session" -> "tasks.withType(io.github.gradlenexus.publishplugin.internal.modern.workflow.DirectSession).matching { it.name.endsWith('_session') }.configureEach"
                    "upload" -> "tasks.withType(io.github.gradlenexus.publishplugin.internal.modern.workflow.WorkflowUpload).configureEach"
                    else -> "tasks.named('closeProbeStagingRepository').configure"
                }
                val mutation = when (failure) {
                    "disabled" -> "enabled=false"
                    "only-if" -> "onlyIf { false }"
                    "input" -> "inputs.file(layout.projectDirectory.file('missing-remote-input'))"
                    "before" -> "doFirst { throw new GradleException('NEXUS_TEST_BEFORE_ACTION_FAILURE') }"
                    "after" -> "doLast { throw new GradleException('NEXUS_TEST_AFTER_ACTION_FAILURE') }"
                    else -> "dependsOn('missingRemotePrerequisite')"
                }
                File(project, "build.gradle").appendText(
                    """
                    abstract class MissingRemoteInput extends DefaultTask {
                        @InputFile abstract RegularFileProperty getRequiredFile()
                        @TaskAction void run() { throw new GradleException('UNREACHABLE_MISSING_INPUT') }
                    }
                    tasks.register('missingRemotePrerequisite',MissingRemoteInput) { requiredFile.set(layout.projectDirectory.file('missing-prerequisite')) }
                    $target { $mutation }
                    """.trimIndent() + "\n"
                )
            }
            repeat(2) { index ->
                val beforeRequests = cases.associate { it.project to it.model.server.requests.size }
                val results = builds.run(requests)
                cases.forEach { (operation, failure, project, model) ->
                    val before = beforeRequests.getValue(project)
                    val result = results.getValue(project)
                    val diagnostic = IndependentGradleBuilds.failureOutput(result)
                    assertThat(diagnostic).contains("NEXUS_WORKFLOW_NOT_SUCCESSFUL")
                    val target = result.tasks.filter {
                        when (operation) {
                            "session" -> it.path.endsWith("_session")
                            "upload" -> it.path.endsWith("_upload")
                            else -> it.path == ":closeProbeStagingRepository"
                        }
                    }
                    when (failure) {
                        "disabled", "only-if" -> assertThat(target.map { it.outcome }).containsExactly(TaskOutcome.SKIPPED)
                        "prerequisite" -> assertThat(result.task(":missingRemotePrerequisite")!!.outcome).isEqualTo(TaskOutcome.FAILED)
                        else -> assertThat(target.map { it.outcome }).containsExactly(TaskOutcome.FAILED)
                    }
                    if (failure == "before") assertThat(diagnostic).contains("NEXUS_TEST_BEFORE_ACTION_FAILURE")
                    cache(result, index)
                    val requests = model.server.requests.drop(before)
                    val posts = requests.filter { it.first == "POST" }.map { it.second }
                    assertThat(posts.count { it.endsWith("/start") }).isEqualTo(if (operation == "session" && failure != "after") 0 else 1)
                    val puts = requests.filter { it.first == "PUT" }
                    if (operation == "close" || (operation == "upload" && failure == "after")) assertThat(puts).isNotEmpty() else assertThat(puts).isEmpty()
                    assertThat(posts.count { it.endsWith("/bulk/close") }).isEqualTo(if (operation == "close" && failure == "after") 1 else 0)
                    assertThat(posts.none { it.endsWith("/promote") }).isTrue()
                    val action = File(workspace(project), "$operation.json")
                    if (failure == "after") {
                        assertThat(IndependentGradleBuilds.failureOutput(result)).contains("NEXUS_TEST_AFTER_ACTION_FAILURE")
                        val receipt = Gson().fromJson(action.readText(), Map::class.java)
                        val plan = Gson().fromJson(File(workspace(project), "plan.json").readText(), Map::class.java)
                        assertThat(receipt["invocation"]).isEqualTo(plan["invocation"])
                        assertThat(receipt["repository"]).isEqualTo("probe")
                        assertThat(receipt["operation"]).isEqualTo(operation)
                        assertThat(receipt["repositoryId"].toString()).startsWith("created-")
                    } else {
                        assertThat(action).doesNotExist()
                    }
                    assertThat(File(workspace(project), "release.json")).doesNotExist()
                }
            }
        } finally {
            models.forEach { it.close() }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `remote actions remain fresh with build cache and user cache opt in`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model)
            File(project, "build.gradle").appendText(
                """
                class AlwaysCache implements org.gradle.api.specs.Spec<Task>, Serializable {
                    boolean isSatisfiedBy(Task task) { true }
                }
                tasks.withType(io.github.gradlenexus.publishplugin.internal.modern.workflow.WorkflowPolicy).configureEach {
                    outputs.cacheIf('user opt in',new AlwaysCache())
                }
                """.trimIndent() + "\n"
            )
            val build = ModernGradleBuild(project, false, runtime)
            val invocations = mutableSetOf<String>()
            var before = 0
            repeat(2) { index ->
                val result = build.runWithBuildCache(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                cache(result, index)
                assertThat(model.created.get()).isEqualTo(index + 1)
                val receipt = Gson().fromJson(File(workspace(project), "release.json").readText(), Map::class.java)
                assertThat(invocations.add(receipt["invocation"].toString())).isTrue()
                result.tasks.filter { it.path.endsWith("_session") || it.path.endsWith("_upload") || it.path in listOf(":closeProbeStagingRepository", ":releaseProbeStagingRepository") }.forEach { task -> assertThat(task.outcome).isEqualTo(TaskOutcome.SUCCESS) }
                val posts = model.server.requests.drop(before).filter { it.first == "POST" }
                assertThat(posts).hasSize(3)
                assertThat(posts.count { it.second.endsWith("/bulk/close") }).isEqualTo(1)
                assertThat(posts.count { it.second.endsWith("/promote") }).isEqualTo(1)
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `protocol failures block release and later explicit recovery stays independent`(runtime: ModernRuntime) {
        listOf("close-http", "release-http", "malformed", "transitioning", "redirect", "auth").forEach { mode ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model)
                val build = ModernGradleBuild(project, false, runtime)
                val tasks = arrayOf(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                build.storeAndReuse(*tasks)
                model.failure = mode
                val diagnostic = when (mode) {
                    "close-http", "release-http", "redirect" -> "NEXUS_TRANSITION_HTTP_FAILURE"
                    "malformed" -> "NEXUS_INVALID_TRANSITION_RESPONSE"
                    "transitioning" -> "NEXUS_TRANSITION_TIMEOUT"
                    else -> "NEXUS_TRANSITION_AUTHENTICATION_REJECTED"
                }
                repeat(2) {
                    val before = model.server.requests.size
                    val result = build.failContinuingWithDiagnostic(diagnostic, *tasks)
                    // Only the server behavior changed; these exact Gradle inputs were already stored.
                    assertThat(result.output).contains("Configuration cache entry reused.")
                    val delta = model.server.requests.drop(before)
                    val posts = delta.filter { it.first == "POST" }.map { it.second }
                    assertThat(posts.count { it.endsWith("/start") }).isEqualTo(1)
                    assertThat(posts.count { it.endsWith("/bulk/close") }).isEqualTo(1)
                    assertThat(posts.count { it.endsWith("/promote") }).isEqualTo(if (mode == "release-http") 1 else 0)
                    assertThat(delta.none { it.first == "DELETE" || it.second.contains("/drop") || it.second.contains("redirect-target") }).isTrue()
                    assertThat(File(workspace(project), "release.json")).doesNotExist()
                    if (mode == "transitioning") assertThat(delta.count { it.first == "GET" && it.second.contains("/staging/repository/") }).isEqualTo(1)
                }
                model.failure = ""
                model.seed("later-recovery")
                val before = model.server.requests.size
                val recovered = build.runRecovery(StagingRecoveryTask(":closeProbeStagingRepository", "later-recovery"), StagingRecoveryTask(":releaseProbeStagingRepository", "later-recovery"))
                assertThat(recovered.tasks.none { it.path.endsWith("_session") || it.path.endsWith("_validate") || it.path.endsWith("_upload") || it.path.startsWith(":child:") }).isTrue()
                assertThat(model.server.requests.drop(before).filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `standalone transition without explicit ID cannot create or discover a session`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model)
            val build = ModernGradleBuild(project, false, runtime)
            repeat(2) { index ->
                val result = build.failContinuingWithDiagnostic("NEXUS_EXPLICIT_STAGING_ID_REQUIRED", ":closeAndReleaseProbeStagingRepository")
                cache(result, index)
                assertThat(model.server.requests).isEmpty()
                assertThat(File(workspace(project), "plan.json")).doesNotExist()
                assertThat(File(workspace(project), "close.json")).doesNotExist()
                assertThat(File(workspace(project), "release.json")).doesNotExist()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `selected public acquisition alone admits lifecycle only after its successful action`(runtime: ModernRuntime) {
        listOf("initialize", "find").forEach { operation ->
            listOf("success", "only-if", "after").forEach { failure ->
                val project = directory()
                NexusModel(project).use { model ->
                    configure(project, model)
                    val task = ":${operation}ProbeStagingRepository"
                    if (failure != "success") File(project, "build.gradle").appendText("tasks.named('${task.removePrefix(":")}') { ${if (failure == "only-if") "onlyIf { false }" else "doLast { throw new GradleException('NEXUS_TEST_OPERATION_AFTER_RECEIPT') }"} }\n")
                    val build = ModernGradleBuild(project, false, runtime)
                    var before = 0
                    repeat(2) { index ->
                        val result = if (failure == "success") build.run(task, ":closeAndReleaseProbeStagingRepository") else build.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL $task", task, ":closeAndReleaseProbeStagingRepository")
                        cache(result, index)
                        assertThat(result.tasks.none { it.path.startsWith(":child:") || it.path.startsWith(":other:") || it.path.endsWith("_session") || it.path.endsWith("_upload") }).isTrue()
                        val delta = model.server.requests.drop(before)
                        assertThat(delta.none { it.first == "PUT" }).isTrue()
                        assertThat(delta.count { it.first == "POST" && it.second.endsWith("/start") }).isEqualTo(if (operation == "initialize" && failure != "only-if") 1 else 0)
                        assertThat(delta.count { it.first == "GET" && it.second.contains("/profile_repositories/") }).isEqualTo(if (operation == "find" && failure != "only-if") 1 else 0)
                        assertThat(delta.count { it.first == "POST" && it.second.endsWith("/bulk/close") }).isEqualTo(if (failure == "success") 1 else 0)
                        assertThat(delta.count { it.first == "POST" && it.second.endsWith("/promote") }).isEqualTo(if (failure == "success") 1 else 0)
                        if (failure == "after") {
                            assertThat(result.output).contains("NEXUS_TEST_OPERATION_AFTER_RECEIPT")
                            assertThat(File(workspace(project), "$operation-session.json")).isFile()
                        }
                        if (failure != "success") assertThat(File(workspace(project), "release.json")).doesNotExist()
                        before = model.server.requests.size
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `empty automatic lifecycle stays local and configured resume uses its actual ID`(runtime: ModernRuntime) {
        listOf("empty", "resume").forEach { mode ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model)
                if (mode == "resume") File(project, "settings.gradle").appendText("nexusPublishing.repositories.probe.stagingRepositoryId.set('configured-resume')\n")
                val build = ModernGradleBuild(project, false, runtime)
                var before = 0
                repeat(2) { index ->
                    if (mode == "resume") model.seed("configured-resume")
                    val result = build.run(if (mode == "empty") ":empty:publishToProbe" else ":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                    cache(result, index)
                    val delta = model.server.requests.drop(before)
                    if (mode == "empty") {
                        assertThat(delta).isEmpty()
                        val released = Gson().fromJson(File(workspace(project), "release.json").readText(), Map::class.java)
                        assertThat(released["repositoryId"]).isEqualTo("")
                    } else {
                        assertThat(delta.filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                        assertThat(delta.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/service/local/staging/deployByRepositoryId/configured-resume/") }
                        assertThat(result.output).contains("id=configured-resume")
                    }
                    before = model.server.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `included public transition cannot borrow outer success under overridden build identity`(runtime: ModernRuntime) {
        listOf("only-if", "after").forEach { failure ->
            val project = directory()
            val included = File(project, "included").apply { mkdirs() }
            NexusModel(project).use { outer ->
                NexusModel(included).use { inner ->
                    configure(project, outer)
                    configure(included, inner)
                    File(included, "settings.gradle").appendText("rootProject.name='different-inner-name'\n")
                    File(project, "settings.gradle").appendText("includeBuild('included') { name='renamed-inclusion' }\n")
                    val tasks = arrayOf(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository", ":renamed-inclusion:child:publishToProbe", ":renamed-inclusion:closeAndReleaseProbeStagingRepository")
                    val build = ModernGradleBuild(project, false, runtime)
                    build.storeAndReuse(*tasks)
                    File(included, "build.gradle").appendText("tasks.named('closeProbeStagingRepository') { ${if (failure == "only-if") "onlyIf { false }" else "doLast { throw new GradleException('NEXUS_TEST_INNER_AFTER_RECEIPT') }"} }\n")
                    var outerBefore = outer.server.requests.size
                    var innerBefore = inner.server.requests.size
                    repeat(2) { index ->
                        val result = build.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL :closeProbeStagingRepository", *tasks)
                        cache(result, index)
                        assertThat(result.task(":closeProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                        assertThat(result.task(":releaseProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                        assertThat(result.task(":renamed-inclusion:closeProbeStagingRepository")!!.outcome).isEqualTo(if (failure == "only-if") TaskOutcome.SKIPPED else TaskOutcome.FAILED)
                        val outerDelta = outer.server.requests.drop(outerBefore)
                        val innerDelta = inner.server.requests.drop(innerBefore)
                        assertThat(outerDelta.count { it.first == "POST" && it.second.endsWith("/promote") }).isEqualTo(1)
                        assertThat(innerDelta.count { it.first == "POST" && it.second.endsWith("/bulk/close") }).isEqualTo(if (failure == "after") 1 else 0)
                        assertThat(innerDelta.none { it.first == "POST" && it.second.endsWith("/promote") }).isTrue()
                        assertThat(innerDelta.filter { it.first == "PUT" }).isNotEmpty()
                        assertThat(File(workspace(project), "release.json")).isFile()
                        assertThat(File(workspace(included), "release.json")).doesNotExist()
                        if (failure == "after") {
                            assertThat(result.output).contains("NEXUS_TEST_INNER_AFTER_RECEIPT")
                            assertThat(File(workspace(included), "close.json")).isFile()
                        }
                        outerBefore = outer.server.requests.size
                        innerBefore = inner.server.requests.size
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `distinct authenticated repositories isolate failed lifecycle under simplified aggregate`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { first ->
            NexusModel(File(project, "second-evidence").apply { mkdirs() }).use { second ->
                configure(project, first)
                val firstUser = "first-${UUID.randomUUID()}"
                val firstPassword = "first-password-${UUID.randomUUID()}"
                val secondUser = "second-${UUID.randomUUID()}"
                val secondPassword = "second-password-${UUID.randomUUID()}"
                first.server.expect(firstUser, firstPassword)
                second.server.expect(secondUser, secondPassword)
                File(project, "settings.gradle").appendText(
                    """
                nexusPublishing.repositories.probe { username.set('$firstUser');password.set('$firstPassword') }
                nexusPublishing.repositories.create('second') {
                    nexusUrl.set(uri('${second.server.endpoint}service/local'))
                    snapshotRepositoryUrl.set(uri('${second.server.endpoint}direct/'))
                    stagingProfileId.set('profile');allowInsecureProtocol.set(true)
                    username.set('$secondUser');password.set('$secondPassword')
                }
                    """.trimIndent() + "\n"
                )
                val tasks = arrayOf(":child:publishToProbe", ":other:publishToSecond", ":closeAndReleaseStagingRepositories")
                val build = ModernGradleBuild(project, false, runtime)
                build.storeAndReuse(*tasks)
                first.failure = "close-http"
                var firstBefore = first.server.requests.size
                var secondBefore = second.server.requests.size
                repeat(2) {
                    val result = build.failContinuingWithDiagnostic("NEXUS_TRANSITION_HTTP_FAILURE", *tasks)
                    assertThat(result.output).contains("Configuration cache entry reused.")
                    assertThat(result.task(":releaseSecondStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                    val a = first.server.requests.drop(firstBefore)
                    val b = second.server.requests.drop(secondBefore)
                    assertThat(a.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.contains("/probe/child/") }
                    assertThat(a.none { it.first == "POST" && it.second.endsWith("/promote") }).isTrue()
                    assertThat(b.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.contains("/probe/other/") }
                    assertThat(b.count { it.first == "POST" && it.second.endsWith("/bulk/close") }).isEqualTo(1)
                    assertThat(b.count { it.first == "POST" && it.second.endsWith("/promote") }).isEqualTo(1)
                    val successful = File(workspace(project, "second"), "release.json").readText()
                    assertThat(listOf(firstUser, firstPassword, secondUser, secondPassword).none { successful.contains(it) || result.output.contains(it) }).isTrue()
                    assertThat(File(workspace(project, "probe"), "release.json")).doesNotExist()
                    firstBefore = first.server.requests.size
                    secondBefore = second.server.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `different projects actually prepare concurrently before staged lifecycle on cache storage and hit`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            LifecycleRendezvous().use { rendezvous ->
                configure(project, model)
                listOf("child", "other").forEach { name ->
                    File(project, "$name/build.gradle").appendText(
                        """
                        abstract class ConcurrentArtifact extends DefaultTask {
                            @Input abstract Property<String> getEndpoint()
                            @OutputFile abstract RegularFileProperty getArtifact()
                            @TaskAction void create() {
                                def connection = new URL(endpoint.get()).openConnection()
                                connection.connectTimeout = 10000; connection.readTimeout = 15000
                                connection.inputStream.withCloseable { if (it.getText('UTF-8') != 'both-entered') throw new GradleException('NEXUS_CONCURRENT_PREPARATION_FAILED') }
                                artifact.get().asFile.parentFile.mkdirs()
                                artifact.get().asFile.setText('concurrent-artifact','UTF-8')
                            }
                        }
                        def concurrentArtifact=tasks.register('concurrentArtifact',ConcurrentArtifact) {
                            endpoint.set('${rendezvous.endpoint}$name')
                            artifact.set(layout.buildDirectory.file('concurrent.txt'));outputs.upToDateWhen { false }
                        }
                        publishing.publications.main.artifact(concurrentArtifact.flatMap { it.artifact }) { classifier='concurrent';extension='txt' }
                        """.trimIndent() + "\n"
                    )
                }
                var invocation = 0
                ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publishToProbe", ":other:publishToProbe", ":closeAndReleaseProbeStagingRepository") {
                    invocation++
                    rendezvous.assertComplete(invocation)
                    assertThat(model.created.get()).isEqualTo(invocation)
                    assertThat(model.server.files.keys.count { it.endsWith("-concurrent.txt") }).isEqualTo(2 * invocation)
                }
            }
        }
    }

    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-lifecycle/${UUID.randomUUID()}/consumer").apply { mkdirs() }
    private fun workspace(project: File, repository: String = "probe"): File {
        val identity = project.toPath().toAbsolutePath().normalize().toString() + "\u0000" + repository
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(project, "build/nexus-publishing/workflows/nexus_$hash")
    }
    private fun cache(result: org.gradle.testkit.runner.BuildResult, index: Int) {
        if (index == 0) {
            assertThat(result.output.contains("Configuration cache entry stored.") || result.output.contains("Configuration cache entry updated for no projects, no projects up-to-date.")).isTrue()
        } else {
            assertThat(result.output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
        }
    }
    private fun configure(project: File, model: NexusModel) {
        File(project, "fixture-endpoint.txt").writeText(model.server.endpoint.toString())
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            def fixtureEndpoint = providers.fileContents(layout.settingsDirectory.file('fixture-endpoint.txt')).asText.get()
            rootProject.name='lifecycle'
            include 'child','other','empty'
            nexusPublishing.repositories.create('probe') {
                nexusUrl.set(uri(fixtureEndpoint + 'service/local'))
                snapshotRepositoryUrl.set(uri(fixtureEndpoint + 'direct/'))
                stagingProfileId.set('profile'); allowInsecureProtocol.set(true)
            }
            nexusPublishing.transitionCheckOptions.maxRetries.set(0)
            nexusPublishing.transitionCheckOptions.delayBetween.set(java.time.Duration.ZERO)
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle").writeText("group='probe';version='1.0'\n")
        listOf("child", "other").forEach { name ->
            val child = File(project, name).apply { mkdirs() }
            File(child, "build.gradle").writeText(
                """
                plugins { id 'java-library'; id 'maven-publish' }
                group='probe';version='1.0'
                publishing.publications { main(MavenPublication) { from components.java;artifactId='$name' } }
                """.trimIndent() + "\n"
            )
            File(child, "src/main/java/probe/Example.java").apply {
                parentFile.mkdirs()
                writeText("package probe; public class Example {}\n")
            }
        }
        File(project, "empty").mkdirs()
        File(project, "empty/build.gradle").writeText("")
    }

    private class NexusModel(project: File) : java.io.Closeable {
        val server = MemoryRepository(File(project, "http-evidence"))
        val created = AtomicInteger()
        private val found = AtomicInteger()
        private val states = ConcurrentHashMap<String, String>()

        @Volatile var failure = ""
        fun seed(id: String, state: String = "open") {
            states[id] = state
        }
        init {
            server.response = { method, path ->
                when {
                    method == "POST" && path.endsWith("/start") -> {
                        val id = "created-${created.incrementAndGet()}"
                        seed(id)
                        MemoryRepository.Response(201, data = "{\"data\":{\"stagedRepositoryId\":\"$id\"}}")
                    }

                    method == "GET" && path.startsWith("/service/local/staging/profile_repositories/") -> {
                        val id = "found-${found.incrementAndGet()}"
                        seed(id)
                        MemoryRepository.Response(200, data = "{\"data\":[{\"repositoryId\":\"$id\",\"description\":\"probe:lifecycle:1.0\"}]}")
                    }

                    method == "POST" && path.startsWith("/service/local/staging/bulk/") -> {
                        val body = Gson().fromJson(String(server.requests.last().third, Charsets.UTF_8), Map::class.java)
                        val id = (((body["data"] as Map<*, *>)["stagedRepositoryIds"] as List<*>).single()).toString()
                        when {
                            !states.containsKey(id) -> MemoryRepository.Response(404)

                            failure == "close-http" && path.endsWith("/close") -> MemoryRepository.Response(503)

                            failure == "release-http" && path.endsWith("/promote") -> MemoryRepository.Response(503)

                            failure == "auth" -> MemoryRepository.Response(403)

                            failure == "redirect" -> MemoryRepository.Response(302, location = server.endpoint.resolve("redirect-target").toString())

                            path.endsWith("/close") -> {
                                states[id] = "closed"
                                MemoryRepository.Response(201, disconnect = failure == "disconnect")
                            }

                            states[id] == "closed" -> {
                                states[id] = "released"
                                MemoryRepository.Response(201)
                            }

                            else -> MemoryRepository.Response(409)
                        }
                    }

                    method == "GET" && path.startsWith("/service/local/staging/repository/") -> {
                        val id = path.substringAfterLast('/')
                        val state = states[id]
                        when {
                            failure == "malformed" -> MemoryRepository.Response(200, data = "{}")
                            failure == "transitioning" -> MemoryRepository.Response(200, data = "{\"repositoryId\":\"$id\",\"type\":\"open\",\"transitioning\":true}")
                            failure == "wrong-id" -> MemoryRepository.Response(200, data = "{\"repositoryId\":\"another\",\"type\":\"closed\",\"transitioning\":false}")
                            failure == "unknown-transitioning" -> MemoryRepository.Response(200, data = "{\"repositoryId\":\"$id\",\"type\":\"unknown\",\"transitioning\":true}")
                            state == "released" -> MemoryRepository.Response(404, data = "{\"repositoryId\":\"${if (failure == "removal-prefix") id + "0" else id}\"}")
                            else -> MemoryRepository.Response(200, data = "{\"repositoryId\":\"$id\",\"type\":\"$state\",\"transitioning\":false}")
                        }
                    }

                    method == "PUT" && path.startsWith("/service/local/staging/deployByRepositoryId/") -> if (states[path.removePrefix("/service/local/staging/deployByRepositoryId/").substringBefore('/')] == "open") null else MemoryRepository.Response(409)

                    else -> null
                }
            }
        }
        override fun close() =
            server.close()
    }
}

private class LifecycleRendezvous : java.io.Closeable {
    private class Gate {
        val entered = ConcurrentHashMap.newKeySet<String>()
        val ready = java.util.concurrent.CountDownLatch(2)
        val completed = AtomicInteger()
    }
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    private val gates = ConcurrentHashMap<Int, Gate>()
    private val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}/"
    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                val participant = exchange.requestURI.path.removePrefix("/")
                require(participant in listOf("child", "other"))
                val invocation = counts.computeIfAbsent(participant) { AtomicInteger() }.incrementAndGet()
                val gate = gates.computeIfAbsent(invocation) { Gate() }
                check(gate.entered.add(participant))
                gate.ready.countDown()
                val both = gate.ready.await(10, java.util.concurrent.TimeUnit.SECONDS)
                val bytes = (if (both) "both-entered" else "timed-out").toByteArray()
                if (both) gate.completed.incrementAndGet()
                exchange.sendResponseHeaders(if (both) 200 else 500, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }
        server.start()
    }
    fun assertComplete(invocation: Int) {
        val gate = gates[invocation]!!
        assertThat(gate.entered).containsExactlyInAnyOrder("child", "other")
        assertThat(gate.completed.get()).isEqualTo(2)
        assertThat(counts.values.map { it.get() }).containsExactly(invocation, invocation)
    }
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
    }
}
