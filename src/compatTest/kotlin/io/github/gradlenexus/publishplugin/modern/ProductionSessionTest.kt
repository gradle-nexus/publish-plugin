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

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real root task graph and the packaged Nexus 2 acquisition client against a disposable HTTP endpoint. */
internal class ProductionSessionTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `automatic staged union initializes once and uploads to a fresh session on cache hit`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            val created = AtomicInteger()
            remote.response = { method, path ->
                if (method == "POST" && path.endsWith("/start")) createdResponse(created) else null
            }
            configure(project, remote.endpoint.toString())
            var before = 0
            ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publishToProbe", ":other:publishToProbe") { result ->
                val id = "created-" + created.get()
                assertThat(result.output).contains("NEXUS-WORKFLOW-VALIDATED repository=probe units=[p1, p3]", "NEXUS-WORKFLOW-REPOSITORY repository=probe id=$id")
                assertThat(result.tasks.none { it.path.startsWith(":empty:") || it.path.contains("initializeProbeStagingRepository") }).isTrue()
                val requests = remote.requests.drop(before)
                assertThat(requests.count { it.first == "POST" }).isEqualTo(1)
                val puts = requests.filter { it.first == "PUT" }
                assertThat(puts).isNotEmpty().allMatch { it.second.startsWith("/service/local/staging/deployByRepositoryId/$id/") }
                listOf("child", "other").forEach { path ->
                    assertThat(remote.files["/service/local/staging/deployByRepositoryId/$id/probe/$path/1.0/$path-1.0.jar"]).isEqualTo(File(project, "$path/build/libs/$path-1.0.jar").readBytes())
                }
                before = remote.requests.size
            }
            assertThat(created.get()).isEqualTo(2)
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `selected public initialize or find is adopted without fallback creation`(runtime: ModernRuntime) {
        listOf("initialize", "find").forEach { mode ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                val created = AtomicInteger()
                remote.response = { method, path ->
                    when {
                        method == "POST" && path.endsWith("/start") -> createdResponse(created)
                        method == "GET" && path.endsWith("/profile_repositories/profile") -> MemoryRepository.Response(200, data = """{"data":[{"description":"probe:session:1.0","repositoryId":"found-1"}]}""")
                        else -> null
                    }
                }
                configure(project, remote.endpoint.toString())
                var before = 0
                ModernGradleBuild(project, false, runtime).storeAndReuse(":${mode}ProbeStagingRepository", ":child:publishToProbe") { result ->
                    val id = if (mode == "find") "found-1" else "created-" + created.get()
                    assertThat(result.output).contains("NEXUS-WORKFLOW-ADOPTED repository=probe id=$id")
                    assertThat(result.task(":${mode}ProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                    val requests = remote.requests.drop(before)
                    assertThat(requests.count { it.first == "POST" }).isEqualTo(if (mode == "initialize") 1 else 0)
                    assertThat(requests.count { it.first == "GET" && it.second.endsWith("/profile_repositories/profile") }).isEqualTo(if (mode == "find") 1 else 0)
                    assertThat(requests.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/service/local/staging/deployByRepositoryId/$id/") }
                    before = remote.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `empty automatic publication and deliberate standalone session operations remain distinct`(runtime: ModernRuntime) {
        listOf("empty", "resume", "initialize", "find").forEach { mode ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                val count = AtomicInteger()
                remote.response = { method, path ->
                    when {
                        method == "POST" && path.endsWith("/start") -> createdResponse(count)
                        method == "GET" && path.endsWith("/profile_repositories/profile") -> MemoryRepository.Response(200, data = """{"data":[{"description":"probe:session:1.0 trailing text","repositoryId":"found-empty"},{"description":"prefixprobe:session:1.0","repositoryId":"wrong-prefix"},{"description":"probe:session:1.0suffix","repositoryId":"wrong-suffix"}]}""")
                        else -> null
                    }
                }
                configure(project, remote.endpoint.toString())
                if (mode == "resume") File(project, "settings.gradle").appendText("nexusPublishing.repositories.probe.stagingRepositoryId.set('resume-empty')\n")
                var before = 0
                val task = if (mode == "empty" || mode == "resume") ":empty:publishToProbe" else ":${mode}ProbeStagingRepository"
                ModernGradleBuild(project, false, runtime).storeAndReuse(task) { result ->
                    assertThat(result.tasks.none { it.path.startsWith(":child:") || it.path.startsWith(":other:") }).isTrue()
                    val requests = remote.requests.drop(before)
                    assertThat(requests.none { it.first == "PUT" }).isTrue()
                    if (mode == "empty" || mode == "resume") {
                        assertThat(result.output).contains("empty=true")
                        assertThat(requests).isEmpty()
                        if (mode == "resume") {
                            assertThat(result.output).contains("NEXUS-WORKFLOW-REPOSITORY repository=probe id=resume-empty")
                            assertThat(File(workspace(project), "session.json").readText()).contains("\"repositoryId\":\"resume-empty\"")
                        }
                    } else {
                        assertThat(result.output).contains("NEXUS-WORKFLOW-VALIDATED repository=probe units=[]", "NEXUS-WORKFLOW-REPOSITORY repository=probe id=")
                        assertThat(result.tasks.none { it.path.endsWith("_upload") }).isTrue()
                        assertThat(requests.count { it.first == "POST" }).isEqualTo(if (mode == "initialize") 1 else 0)
                        if (mode == "find") assertThat(result.output).contains("id=found-empty")
                    }
                    before = remote.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `failed skipped or invalid public operations never fall back despite stale warm receipts`(runtime: ModernRuntime) {
        for (operation in listOf("initialize", "find")) {
            for (failure in listOf("disabled", "onlyIf", "input", "before", "after")) {
                val project = directory()
                MemoryRepository(File(project, "http-evidence")).use { remote ->
                    val count = AtomicInteger()
                    remote.response = { method, path ->
                        when {
                            method == "POST" && path.endsWith("/start") -> createdResponse(count)
                            method == "GET" && path.endsWith("/profile_repositories/profile") -> MemoryRepository.Response(200, data = """{"data":[{"description":"probe:session:1.0","repositoryId":"found-warm"}]}""")
                            else -> null
                        }
                    }
                    configure(project, remote.endpoint.toString())
                    val runner = ModernGradleBuild(project, false, runtime)
                    val task = ":${operation}ProbeStagingRepository"
                    runner.storeAndReuse(task, ":child:publishToProbe")
                    val hook = when (failure) {
                        "disabled" -> "enabled = false"
                        "onlyIf" -> "onlyIf { false }"
                        "input" -> "inputs.file(layout.projectDirectory.file('absent-operation-input')).withPropertyName('ownedAbsent')"
                        "before" -> "doFirst { throw new GradleException('OWNED_SESSION_BEFORE_ACTION_FAILURE') }"
                        else -> "doLast { throw new GradleException('OWNED_SESSION_AFTER_RECEIPT_FAILURE') }"
                    }
                    File(project, "build.gradle").appendText("\ntasks.named('${task.substring(1)}') { $hook }\n")
                    var before = remote.requests.size
                    repeat(2) { index ->
                        val result = runner.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL $task", task, ":child:publishToProbe")
                        cache(result, index)
                        assertThat(result.task(task)!!.outcome).isEqualTo(if (failure in listOf("disabled", "onlyIf")) TaskOutcome.SKIPPED else TaskOutcome.FAILED)
                        val requests = remote.requests.drop(before)
                        assertThat(requests.none { it.first == "PUT" }).isTrue()
                        assertThat(requests.count { it.first == "POST" }).isEqualTo(if (failure == "after" && operation == "initialize") 1 else 0)
                        assertThat(requests.count { it.first == "GET" && it.second.endsWith("/profile_repositories/profile") }).isEqualTo(if (failure == "after" && operation == "find") 1 else 0)
                        val receipt = workspace(project).resolve("$operation-session.json")
                        if (failure == "after") {
                            assertThat(result.output).contains("OWNED_SESSION_AFTER_RECEIPT_FAILURE", "NEXUS-WORKFLOW-REPOSITORY repository=probe id=")
                            assertThat(receipt).isFile()
                        } else {
                            assertThat(receipt).doesNotExist()
                        }
                        assertThat(workspace(project).resolve("session.json")).doesNotExist()
                        assertThat(workspace(project).resolve("upload.json")).doesNotExist()
                        before = remote.requests.size
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `ambiguous create and upload failure preserve recovery without replay or implicit fallback`(runtime: ModernRuntime) {
        listOf("lost-create", "failed-upload").forEach { failure ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                val count = AtomicInteger()
                val recovery = java.util.concurrent.atomic.AtomicBoolean()
                remote.response = { method, path ->
                    when {
                        method == "POST" && path.endsWith("/start") -> {
                            val id = "created-${count.incrementAndGet()}"
                            MemoryRepository.Response(201, data = """{"data":{"stagedRepositoryId":"$id"}}""", disconnect = failure == "lost-create", commit = failure == "lost-create")
                        }

                        method == "PUT" && !recovery.get() -> MemoryRepository.Response(403)

                        method == "PUT" && !path.startsWith("/service/local/staging/deployByRepositoryId/created-${count.get()}/") -> MemoryRepository.Response(404)

                        else -> null
                    }
                }
                configure(project, remote.endpoint.toString())
                val runner = ModernGradleBuild(project, false, runtime)
                var before = 0
                repeat(2) { index ->
                    val result = runner.failWithDiagnostic(if (failure == "lost-create") "NEXUS_STAGING_CREATE_AMBIGUOUS" else "Received status code 403 from server", ":child:publishToProbe")
                    cache(result, index)
                    val requests = remote.requests.drop(before)
                    assertThat(count.get()).isEqualTo(index + 1)
                    assertThat(requests.count { it.first == "POST" }).isEqualTo(1)
                    assertThat(requests.none { it.second.contains("/finish") || it.second.contains("/promote") || it.second.contains("/drop") }).isTrue()
                    if (failure == "lost-create") {
                        assertThat(requests.none { it.first == "PUT" }).isTrue()
                        assertThat(workspace(project).resolve("session.json")).doesNotExist()
                    } else {
                        assertThat(result.output).contains("NEXUS-WORKFLOW-REPOSITORY repository=probe id=created-${count.get()}")
                        assertThat(result.output.indexOf("NEXUS-WORKFLOW-REPOSITORY")).isLessThan(result.output.indexOf("Received status code 403 from server"))
                        assertThat(workspace(project).resolve("session.json")).isFile()
                        val puts = requests.filter { it.first == "PUT" }
                        assertThat(puts).isNotEmpty()
                        assertThat(puts).allMatch { it.second.startsWith("/service/local/staging/deployByRepositoryId/created-${count.get()}/") }
                    }
                    assertThat(workspace(project).resolve("upload.json")).doesNotExist()
                    before = remote.requests.size
                }
                val actualId = "created-${count.get()}"
                recovery.set(true)
                File(project, "settings.gradle").appendText("\nnexusPublishing.repositories.probe.stagingRepositoryId.set('$actualId')\n")
                repeat(2) { index ->
                    val result = runner.run(":child:publishToProbe")
                    cache(result, index)
                    assertThat(result.output).contains("NEXUS-WORKFLOW-REPOSITORY repository=probe id=$actualId")
                    val requests = remote.requests.drop(before)
                    assertThat(requests.none { it.first == "POST" || it.second.contains("/profile_repositories/") || it.second.endsWith("/staging/profiles") }).isTrue()
                    assertThat(requests.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/service/local/staging/deployByRepositoryId/$actualId/") }
                    assertThat(count.get()).isEqualTo(2)
                    before = remote.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `conflicting policies and selected union collision fail before acquisition`(runtime: ModernRuntime) {
        for (mode in listOf("both", "resume-initialize", "resume-find", "direct-initialize", "direct-resume", "bad-id", "collision")) {
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                configure(project, remote.endpoint.toString())
                if (mode.startsWith("resume") || mode == "direct-resume") File(project, "settings.gradle").appendText("\nnexusPublishing.repositories.probe.stagingRepositoryId.set('saved')\n")
                if (mode.startsWith("direct")) File(project, "settings.gradle").appendText("\nnexusPublishing.useStaging.set(false)\n")
                if (mode == "bad-id") File(project, "settings.gradle").appendText("\nnexusPublishing.repositories.probe.stagingRepositoryId.set('../invalid')\n")
                if (mode == "collision") File(project, "other/build.gradle").appendText("\npublishing.publications.main.artifactId = 'child'\n")
                val tasks = mutableListOf(":child:publishToProbe")
                if (mode == "both" || mode.endsWith("initialize")) tasks.add(":initializeProbeStagingRepository")
                if (mode == "both" || mode == "resume-find") tasks.add(":findProbeStagingRepository")
                if (mode == "collision") tasks.add(":other:publishToProbe")
                val diagnostic = when {
                    mode.startsWith("direct") -> "NEXUS_SESSION_POLICY_UNSUPPORTED_DIRECT_ROUTE"
                    mode == "bad-id" -> "NEXUS_INVALID_STAGING_IDENTIFIER"
                    mode == "collision" -> "NEXUS_DESTINATION_COLLISION"
                    else -> "NEXUS_CONFLICTING_SESSION_POLICIES"
                }
                val runner = ModernGradleBuild(project, false, runtime)
                repeat(2) { index ->
                    val result = runner.failContinuingWithDiagnostic(diagnostic, *tasks.toTypedArray())
                    cache(result, index)
                    assertThat(remote.requests).isEmpty()
                    assertThat(workspace(project).resolve("session.json")).doesNotExist()
                    assertThat(workspace(project).resolve("upload.json")).doesNotExist()
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `final root snapshot default and explicit staging override win over child release coordinates`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            val count = AtomicInteger()
            remote.response = { method, path -> if (method == "POST" && path.endsWith("/start")) createdResponse(count) else null }
            configure(project, remote.endpoint.toString())
            File(project, "build.gradle").appendText("\nafterEvaluate { version = '2.0-SNAPSHOT' }\n")
            val runner = ModernGradleBuild(project, false, runtime)
            var before = 0
            runner.storeAndReuse(":child:publishToProbe") { result ->
                assertThat(result.output).contains("NEXUS-WORKFLOW-UPLOADED")
                val requests = remote.requests.drop(before)
                assertThat(requests.none { it.first == "POST" }).isTrue()
                assertThat(requests.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/direct/probe/child/1.0/") || it.second.startsWith("/direct/probe/child/maven-metadata.xml") }
                before = remote.requests.size
            }
            File(project, "settings.gradle").appendText("\nnexusPublishing.useStaging.set(true)\n")
            repeat(2) { index ->
                val result = runner.run(":child:publishToProbe")
                cache(result, index)
                val id = "created-${count.get()}"
                val requests = remote.requests.drop(before)
                assertThat(requests.count { it.first == "POST" }).isEqualTo(1)
                assertThat(String(requests.single { it.first == "POST" }.third, Charsets.UTF_8)).contains("probe:session:2.0-SNAPSHOT")
                assertThat(requests.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/service/local/staging/deployByRepositoryId/$id/") }
                before = remote.requests.size
            }
        }
    }

    private fun workspace(project: File): File =
        File(project, "build/nexus-publishing/workflows").listFiles()!!.single()
    private fun cache(result: org.gradle.testkit.runner.BuildResult, index: Int) {
        if (index == 0) {
            assertThat(result.output.contains("Configuration cache entry stored.") || result.output.contains("Configuration cache entry updated for no projects, no projects up-to-date.")).isTrue()
        } else {
            assertThat(result.output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `longest profile final root defaults credentials and configured find policy are effective`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            val count = AtomicInteger()
            remote.response = { method, path ->
                when {
                    method == "GET" && path.endsWith("/staging/profiles") -> MemoryRepository.Response(200, data = """{"data":[{"name":"org","id":"wide"},{"name":"org.example.deep","id":"longest"},{"name":"org.example","id":"middle"},{"name":"explicit.group.deep","id":"override"}]}""")
                    method == "POST" && (path.endsWith("/profiles/longest/start") || path.endsWith("/profiles/override/start")) -> createdResponse(count)
                    method == "GET" && (path.endsWith("/profile_repositories/longest") || path.endsWith("/profile_repositories/override")) -> MemoryRepository.Response(200, data = """{"data":[{"description":"unrelated-chosen","repositoryId":"custom-found"}]}""")
                    else -> null
                }
            }
            configure(project, remote.endpoint.toString())
            File(project, "settings.gradle").appendText("\nnexusPublishing.repositories.probe.stagingProfileId.unset()\n")
            File(project, "build.gradle").appendText("\nafterEvaluate { group = 'org.example.deep'; version = '2.0' }\n")
            val runner = ModernGradleBuild(project, false, runtime)
            var before = 0
            for (generation in 1..2) {
                val username = "owned-session-user-$generation-" + UUID.randomUUID()
                val password = "owned-session-password-$generation-" + UUID.randomUUID()
                remote.expect(username, password)
                File(project, "gradle.properties").writeText("probeUsername=$username\nprobePassword=$password\n")
                repeat(2) { index ->
                    val result = runner.run(":child:publishToProbe")
                    cache(result, index)
                    val requests = remote.requests.drop(before)
                    assertThat(requests.count { it.first == "GET" && it.second.endsWith("/staging/profiles") }).isEqualTo(1)
                    val starts = requests.filter { it.first == "POST" }
                    assertThat(starts).hasSize(1)
                    assertThat(starts.single().second).endsWith("/profiles/longest/start")
                    assertThat(String(starts.single().third, Charsets.UTF_8)).contains("org.example.deep:session:2.0")
                    assertThat(remote.authenticated.drop(before)).isNotEmpty().containsOnly(true)
                    assertThat(result.output.contains(username) || result.output.contains(password)).describedAs("credential values absent from output").isFalse()
                    workspace(project).listFiles()!!.filter { it.extension == "json" }.forEach { file ->
                        assertThat(file.readText().contains(username) || file.readText().contains(password)).describedAs("credential values absent from root records").isFalse()
                    }
                    before = remote.requests.size
                }
            }
            File(project, "settings.gradle").appendText("\nnexusPublishing.packageGroup.set('explicit.group.deep')\nnexusPublishing.repositoryDescription.set('Explicit root description')\n")
            repeat(2) { index ->
                val result = runner.run(":child:publishToProbe")
                cache(result, index)
                val start = remote.requests.drop(before).single { it.first == "POST" }
                assertThat(start.second).endsWith("/profiles/override/start")
                assertThat(String(start.third, Charsets.UTF_8)).contains("Explicit root description")
                before = remote.requests.size
            }
            File(project, "build.gradle").appendText("\ntasks.named('findProbeStagingRepository') { descriptionRegex.set('chosen$') }\n")
            runner.storeAndReuse(":findProbeStagingRepository", ":child:publishToProbe") { result ->
                assertThat(result.output).contains("NEXUS-WORKFLOW-ADOPTED repository=probe id=custom-found")
                assertThat(remote.requests.drop(before).none { it.first == "POST" }).isTrue()
                assertThat(workspace(project).resolve("find-session.json").readText()).contains("\"queryIdentity\":\"")
                before = remote.requests.size
            }
            File(project, "build.gradle").appendText("\ntasks.named('findProbeStagingRepository') { descriptionRegex.set('[') }\n")
            repeat(2) { index ->
                val result = runner.failContinuingWithDiagnostic("NEXUS_INVALID_FIND_PATTERN", ":findProbeStagingRepository", ":child:publishToProbe")
                cache(result, index)
                assertThat(remote.requests.size).isEqualTo(before)
                assertThat(workspace(project).resolve("session.json")).doesNotExist()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `zero multiple malformed and forbidden find results never create a fallback session`(runtime: ModernRuntime) {
        for (response in listOf("zero", "multiple", "malformed", "auth", "insecure", "redirect")) {
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                remote.response = { method, path ->
                    if (method == "GET" && path.endsWith("/profile_repositories/profile")) {
                        when (response) {
                            "zero" -> MemoryRepository.Response(200, data = """{"data":[]}""")
                            "multiple" -> MemoryRepository.Response(200, data = """{"data":[{"description":"probe:session:1.0","repositoryId":"a"},{"description":"probe:session:1.0 trailing","repositoryId":"b"}]}""")
                            "auth" -> MemoryRepository.Response(403)
                            "redirect" -> MemoryRepository.Response(302, location = remote.endpoint.resolve("redirect-target").toString())
                            else -> MemoryRepository.Response(200, data = """{"data":[{"description":"probe:session:1.0","repositoryId":true}]}""")
                        }
                    } else {
                        null
                    }
                }
                configure(project, remote.endpoint.toString())
                if (response == "insecure") File(project, "settings.gradle").appendText("\nnexusPublishing.repositories.probe.allowInsecureProtocol.set(false)\n")
                val diagnostic = when (response) {
                    "zero", "multiple" -> "NEXUS_FIND_REQUIRES_EXACTLY_ONE_REPOSITORY"
                    "auth" -> "NEXUS_AUTHENTICATION_REJECTED"
                    "insecure" -> "NEXUS_INSECURE_HTTP_REJECTED"
                    "redirect" -> "NEXUS_STAGING_HTTP_FAILURE status=302"
                    else -> "NEXUS_INVALID_STAGING_RESPONSE"
                }
                val runner = ModernGradleBuild(project, false, runtime)
                repeat(2) { index ->
                    val result = runner.failContinuingWithDiagnostic(diagnostic, ":findProbeStagingRepository", ":child:publishToProbe")
                    cache(result, index)
                    assertThat(remote.requests.none { it.first == "POST" || it.first == "PUT" || it.second.contains("redirect-target") }).isTrue()
                    if (response == "insecure") assertThat(remote.requests).isEmpty()
                    assertThat(workspace(project).resolve("session.json")).doesNotExist()
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `included public initialize cannot borrow outer success with the same familiar name`(runtime: ModernRuntime) {
        val project = directory()
        val included = File(project, "included").apply { mkdirs() }
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            val count = AtomicInteger()
            remote.response = { method, path -> if (method == "POST" && path.endsWith("/start")) createdResponse(count) else null }
            configure(project, remote.endpoint.toString() + "outer/")
            configure(included, remote.endpoint.toString() + "inner/")
            File(included, "settings.gradle").appendText("\nrootProject.name = 'different-inner-name'\n")
            File(project, "settings.gradle").appendText("\nincludeBuild('included') { name = 'renamed-inclusion' }\n")
            val tasks = arrayOf(":initializeProbeStagingRepository", ":child:publishToProbe", ":renamed-inclusion:initializeProbeStagingRepository", ":renamed-inclusion:child:publishToProbe")
            val runner = ModernGradleBuild(project, false, runtime)
            runner.storeAndReuse(*tasks)
            File(included, "build.gradle").appendText("\ntasks.named('initializeProbeStagingRepository') { onlyIf { false } }\n")
            var before = remote.requests.size
            repeat(2) { index ->
                val result = runner.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL :initializeProbeStagingRepository", *tasks)
                cache(result, index)
                assertThat(result.task(":initializeProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                assertThat(result.task(":renamed-inclusion:initializeProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SKIPPED)
                val requests = remote.requests.drop(before)
                assertThat(requests.count { it.first == "POST" }).isEqualTo(1)
                assertThat(requests.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/outer/service/local/") }
                assertThat(workspace(included).resolve("session.json")).doesNotExist()
                assertThat(workspace(project).resolve("upload.json")).isFile()
                before = remote.requests.size
            }
        }
    }

    private fun createdResponse(counter: AtomicInteger) =
        MemoryRepository.Response(201, data = """{"data":{"stagedRepositoryId":"created-${counter.incrementAndGet()}"}}""")
    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-session/${UUID.randomUUID()}/consumer").apply { mkdirs() }
    private fun configure(project: File, endpoint: String) {
        File(project, "fixture-endpoint.txt").writeText(endpoint.toString())
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            def fixtureEndpoint = providers.fileContents(layout.settingsDirectory.file('fixture-endpoint.txt')).asText.get()
            rootProject.name = 'session'
            include 'child', 'empty', 'other'
            nexusPublishing.repositories.create('probe') {
                nexusUrl.set(uri(fixtureEndpoint + 'service/local'))
                snapshotRepositoryUrl.set(uri(fixtureEndpoint + 'direct/'))
                stagingProfileId.set('profile')
                allowInsecureProtocol.set(true)
            }
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle").writeText("group = 'probe'; version = '1.0'\n")
        listOf("child", "other").forEach { path ->
            val child = File(project, path).apply { mkdirs() }
            File(child, "build.gradle").writeText(
                """
                plugins { id 'java-library'; id 'maven-publish' }
                group = 'probe'; version = '1.0'
                publishing.publications { main(MavenPublication) { from components.java; artifactId = '$path' } }
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
}
