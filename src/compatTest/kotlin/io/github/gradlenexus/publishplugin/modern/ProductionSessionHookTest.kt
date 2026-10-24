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
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Supported public hooks compose with existing admission; no internal orchestration types in scripts. */
internal class ProductionSessionHookTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    @Tag("settings-smoke")
    fun `failed public session prerequisite blocks acquisition under continue`(runtime: ModernRuntime) {
        sessionPrerequisites(runtime, listOf("prerequisite"))
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `session prerequisites guard acquisition and shared tasks deduplicate public adoption and direct actions`(runtime: ModernRuntime) {
        sessionPrerequisites(runtime, listOf("initialize", "find", "direct"))
    }

    private fun sessionPrerequisites(runtime: ModernRuntime, modes: List<String>) {
        modes.forEach { mode ->
            val project = directory()
            Nexus(project).use { model ->
                configure(project, model, mode != "direct")
                File(project, "build.gradle.kts").appendText(
                    """
                    val prerequisite = tasks.register("sessionPrerequisite") {
                        doLast {
                            logger.lifecycle("PUBLIC_SESSION_PREREQUISITE")
                            ${if (mode == "prerequisite") "throw GradleException(\"PUBLIC_SESSION_PREREQUISITE_FAILED\")" else ""}
                        }
                    }
                    tasks.withType<NexusRepositorySession>().configureEach {
                        check(repositoryAlias.get() == "probe")
                        dependsOn(prerequisite)
                        doFirst { logger.lifecycle("PUBLIC_SESSION_ACTION") }
                    }
                    tasks.withType<FindNexusStagingRepository>().configureEach { descriptionRegex.set("selected find") }
                    """.trimIndent() + "\n"
                )
                val requests = if (mode in listOf("initialize", "find")) arrayOf(":${mode}ProbeStagingRepository", ":child:publishToProbe", ":closeAndReleaseProbeStagingRepository") else arrayOf(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                val build = ModernGradleBuild(project, false, runtime)
                var before = 0
                repeat(2) { index ->
                    val result = if (mode == "prerequisite") build.failContinuingWithDiagnostic("PUBLIC_SESSION_PREREQUISITE_FAILED", *requests) else build.run(*requests)
                    cache(result, index)
                    val delta = model.server.requests.drop(before)
                    assertThat(result.output.lines().count { it == "PUBLIC_SESSION_PREREQUISITE" }).isEqualTo(1)
                    assertThat(result.task(":sessionPrerequisite")!!.outcome).isEqualTo(if (mode == "prerequisite") TaskOutcome.FAILED else TaskOutcome.SUCCESS)
                    if (mode == "prerequisite") {
                        assertThat(delta).isEmpty()
                        assertThat(result.output).doesNotContain("PUBLIC_SESSION_ACTION")
                    } else {
                        assertThat(result.output.lines().count { it == "PUBLIC_SESSION_ACTION" }).isEqualTo(if (mode in listOf("initialize", "find")) 2 else 1)
                        assertThat(delta.count { it.first == "POST" && it.second.endsWith("/start") }).isEqualTo(if (mode == "initialize") 1 else 0)
                        assertThat(delta.count { it.first == "GET" && it.second.contains("/profile_repositories/") }).isEqualTo(if (mode == "find") 1 else 0)
                        assertThat(delta.filter { it.first == "PUT" }).isNotEmpty()
                        assertThat(delta.count { it.first == "POST" && it.second.endsWith("/bulk/close") }).isEqualTo(if (mode == "direct") 0 else 1)
                        assertThat(delta.count { it.first == "POST" && it.second.endsWith("/bulk/promote") }).isEqualTo(if (mode == "direct") 0 else 1)
                        if (mode == "direct") {
                            assertThat(result.task(":closeProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SKIPPED)
                            assertThat(result.task(":releaseProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SKIPPED)
                            assertThat(delta.filter { it.first == "PUT" }.all { it.second.startsWith("/direct/probe/child/") }).isTrue()
                        }
                    }
                    before = model.server.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `warm typed session skip and after receipt failure cannot admit upload or transitions on cache hits`(runtime: ModernRuntime) {
        listOf("only-if", "after").forEach { mode ->
            val project = directory()
            Nexus(project).use { model ->
                configure(project, model)
                val flag = File(project, "session-failure")
                File(project, "build.gradle.kts").appendText(
                    """
                    tasks.withType<NexusRepositorySession>().configureEach {
                        inputs.files("${flag.invariantSeparatorsPath}").withPropertyName("sessionHookFlag").optional()
                        ${if (mode == "only-if") "onlyIf { !java.io.File(\"${flag.invariantSeparatorsPath}\").exists() }" else "doLast { if (java.io.File(\"${flag.invariantSeparatorsPath}\").exists()) throw GradleException(\"PUBLIC_SESSION_AFTER_FAILURE\") }"}
                    }
                    """.trimIndent() + "\n"
                )
                val build = ModernGradleBuild(project, false, runtime)
                build.storeAndReuse(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                var previousInvocation = record(project, "session.json")["invocation"]
                assertThat(File(workspace(project), "release.json")).isFile()
                flag.writeText("fail\n")
                var before = model.server.requests.size
                repeat(2) {
                    val result = build.failContinuingWithDiagnostic(if (mode == "after") "PUBLIC_SESSION_AFTER_FAILURE" else "NEXUS_WORKFLOW_NOT_SUCCESSFUL", ":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                    assertThat(result.output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
                    val session = result.tasks.single { it.path.endsWith("_session") }
                    assertThat(session.outcome).isEqualTo(if (mode == "after") TaskOutcome.FAILED else TaskOutcome.SKIPPED)
                    val delta = model.server.requests.drop(before)
                    assertThat(delta.none { it.first == "PUT" || it.second.endsWith("/bulk/close") || it.second.endsWith("/bulk/promote") }).isTrue()
                    if (mode == "after") {
                        assertThat(delta.count { it.first == "POST" && it.second.endsWith("/start") }).isEqualTo(1)
                        val current = record(project, "session.json")
                        assertThat(current["invocation"]).isNotEqualTo(previousInvocation)
                        previousInvocation = current["invocation"]
                        val id = current["repositoryId"] as String
                        assertThat(model.states[id]).isEqualTo("open")
                        assertThat(result.output).contains("id=$id")
                    } else {
                        assertThat(delta).isEmpty()
                        assertThat(File(workspace(project), "session.json")).doesNotExist()
                    }
                    assertThat(File(workspace(project), "upload.json")).doesNotExist()
                    assertThat(File(workspace(project), "close.json")).doesNotExist()
                    assertThat(File(workspace(project), "release.json")).doesNotExist()
                    before = model.server.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `warm public upload hooks fail at their actual phase and never admit close`(runtime: ModernRuntime) {
        listOf("prerequisite", "before", "after", "only-if").forEach { mode ->
            val project = directory()
            Nexus(project).use { model ->
                configure(project, model)
                val flag = File(project, "upload-failure")
                File(project, "build.gradle.kts").appendText(
                    """
                    val prerequisite = tasks.register("uploadPrerequisite") {
                        inputs.files("${flag.invariantSeparatorsPath}").withPropertyName("uploadHookFlag").optional()
                        doLast { ${if (mode == "prerequisite") "if (java.io.File(\"${flag.invariantSeparatorsPath}\").exists()) throw GradleException(\"PUBLIC_UPLOAD_PREREQUISITE_FAILED\")" else "logger.lifecycle(\"PUBLIC_UPLOAD_PREREQUISITE\")"} }
                    }
                    tasks.withType<NexusRemotePublish>().configureEach {
                        check(repositoryAlias.get() == "probe")
                        dependsOn(prerequisite)
                        inputs.files("${flag.invariantSeparatorsPath}").withPropertyName("uploadHookFlag").optional()
                        ${when (mode) {
                        "only-if" -> "onlyIf { !java.io.File(\"${flag.invariantSeparatorsPath}\").exists() }"
                        "before", "after" -> "${if (mode == "before") "doFirst" else "doLast"} { if (java.io.File(\"${flag.invariantSeparatorsPath}\").exists()) throw GradleException(\"PUBLIC_UPLOAD_${mode.toUpperCase()}_FAILURE\") }"
                        else -> ""
                    }}
                    }
                    """.trimIndent() + "\n"
                )
                val build = ModernGradleBuild(project, false, runtime)
                build.storeAndReuse(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                var previousInvocation = record(project, "upload.json")["invocation"]
                flag.writeText("fail\n")
                var before = model.server.requests.size
                repeat(2) {
                    val diagnostic = when (mode) {
                        "prerequisite" -> "PUBLIC_UPLOAD_PREREQUISITE_FAILED"
                        "only-if" -> "NEXUS_WORKFLOW_NOT_SUCCESSFUL"
                        else -> "PUBLIC_UPLOAD_${mode.toUpperCase()}_FAILURE"
                    }
                    val result = build.failContinuingWithDiagnostic(diagnostic, ":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                    assertThat(result.output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
                    val delta = model.server.requests.drop(before)
                    val starts = delta.count { it.first == "POST" && it.second.endsWith("/start") }
                    if (mode == "prerequisite") {
                        assertThat(result.task(":uploadPrerequisite")!!.outcome).isEqualTo(TaskOutcome.FAILED)
                        assertThat(starts).isBetween(0, 1) // Upload prerequisites do not promise to precede acquisition.
                    } else {
                        assertThat(starts).isEqualTo(1)
                        val upload = result.tasks.single { it.path.endsWith("_upload") }
                        assertThat(upload.outcome).isEqualTo(if (mode == "only-if") TaskOutcome.SKIPPED else TaskOutcome.FAILED)
                    }
                    assertThat(delta.none { it.second.endsWith("/bulk/close") || it.second.endsWith("/bulk/promote") }).isTrue()
                    if (mode == "after") {
                        assertThat(delta.filter { it.first == "PUT" }).isNotEmpty()
                        val current = record(project, "upload.json")
                        assertThat(current["invocation"]).isNotEqualTo(previousInvocation)
                        previousInvocation = current["invocation"]
                        assertThat(current["operation"]).isEqualTo("upload")
                        val session = record(project, "session.json")
                        assertThat(current["invocation"]).isEqualTo(session["invocation"])
                        assertThat(current["repositoryId"]).isEqualTo(session["repositoryId"])
                        assertThat(delta.filter { it.first == "PUT" }.all { it.second.startsWith("/service/local/staging/deployByRepositoryId/" + session["repositoryId"] + "/") }).isTrue()
                    } else {
                        assertThat(delta.none { it.first == "PUT" }).isTrue()
                        assertThat(File(workspace(project), "upload.json")).doesNotExist()
                    }
                    if (starts == 1) {
                        val id = record(project, "session.json")["repositoryId"] as String
                        assertThat(result.output).contains("id=$id")
                        assertThat(model.states[id]).isEqualTo("open")
                    }
                    assertThat(File(workspace(project), "close.json")).doesNotExist()
                    assertThat(File(workspace(project), "release.json")).doesNotExist()
                    before = model.server.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `standalone explicit recovery does not enroll failing session prerequisites`(runtime: ModernRuntime) {
        val project = directory()
        Nexus(project).use { model ->
            configure(project, model)
            File(project, "build.gradle.kts").appendText(
                """
                val prerequisite = tasks.register("sessionPrerequisite") { doLast { throw GradleException("SESSION_PREREQUISITE_MUST_NOT_RUN") } }
                tasks.withType<NexusRepositorySession>().configureEach { dependsOn(prerequisite) }
                """.trimIndent() + "\n"
            )
            val build = ModernGradleBuild(project, false, runtime)
            var before = 0
            repeat(2) { index ->
                model.states["recovery"] = "open"
                val result = build.runRecovery(StagingRecoveryTask(":closeProbeStagingRepository", "recovery"), StagingRecoveryTask(":releaseProbeStagingRepository", "recovery"))
                cache(result, index)
                assertThat(result.tasks.none { it.path == ":sessionPrerequisite" || it.path.startsWith(":child:") || it.path.endsWith("_session") || it.path == ":initializeProbeStagingRepository" || it.path == ":findProbeStagingRepository" }).isTrue()
                assertThat(result.output).doesNotContain("SESSION_PREREQUISITE_MUST_NOT_RUN")
                val delta = model.server.requests.drop(before)
                assertThat(delta.filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                assertThat(delta.none { it.first == "PUT" || it.second.endsWith("/start") || it.second.contains("profile_repositories") }).isTrue()
                before = model.server.requests.size
            }
        }
    }

    private fun cache(result: BuildResult, index: Int) {
        assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
    }
    private fun workspace(project: File) =
        File(project, "build/nexus-publishing/workflows").listFiles()!!.single()
    private fun record(project: File, name: String): Map<*, *> =
        Gson().fromJson(File(workspace(project), name).readText(), Map::class.java)

    private fun configure(project: File, model: Nexus, staging: Boolean = true) {
        val repository = File(project.parentFile, "repository")
        val version = "2.0.1-SNAPSHOT"
        fun pom(group: String, artifact: String, dependencies: String = "", packaging: String = "jar") {
            val file = File(repository, "${group.replace('.', '/')}/$artifact/$version/$artifact-$version.pom")
            file.parentFile.mkdirs()
            file.writeText("""<project><modelVersion>4.0.0</modelVersion><groupId>$group</groupId><artifactId>$artifact</artifactId><version>$version</version><packaging>$packaging</packaging>$dependencies</project>""")
        }
        File(repository, "io/github/gradle-nexus/publish-plugin/$version/publish-plugin-$version.jar").apply {
            parentFile.mkdirs()
            ModernTestPaths.candidateJar.copyTo(this)
        }
        pom("io.github.gradle-nexus", "publish-plugin")
        pom("io.github.gradle-nexus.publish-plugin", "io.github.gradle-nexus.publish-plugin.gradle.plugin", "<dependencies><dependency><groupId>io.github.gradle-nexus</groupId><artifactId>publish-plugin</artifactId><version>$version</version></dependency></dependencies>", "pom")
        File(project, "settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { maven { url = uri("${repository.toURI()}") } } }
            plugins { id("io.github.gradle-nexus.publish-plugin") version "$version" }
            rootProject.name="session-hooks"
            include("child")
            nexusPublishing {
                useStaging.set($staging)
                transitionCheckOptions { maxRetries.set(0);delayBetween.set(java.time.Duration.ZERO) }
                repositories { create("probe") {
                    nexusUrl.set(java.net.URI.create("${model.server.endpoint}service/local/"))
                    snapshotRepositoryUrl.set(java.net.URI.create("${model.server.endpoint}direct/"))
                    stagingProfileId.set("profile");allowInsecureProtocol.set(true)
                } }
            }
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle.kts").writeText(
            """
            import io.github.gradlenexus.publishplugin.tasks.NexusRepositorySession
            import io.github.gradlenexus.publishplugin.tasks.NexusRemotePublish
            import io.github.gradlenexus.publishplugin.tasks.FindNexusStagingRepository
            group="probe";version="1.0"
            check(tasks.withType<NexusRepositorySession>().names.size == 3)
            check(tasks.withType<FindNexusStagingRepository>().names == setOf("findProbeStagingRepository"))
            """.trimIndent() + "\n"
        )
        File(project, "child/build.gradle").apply {
            parentFile.mkdirs()
            writeText("plugins { id 'java-library';id 'maven-publish' };group='probe';version='1.0';publishing.publications { main(MavenPublication) { from components.java;artifactId='child' } }\n")
        }
        File(project, "child/src/main/java/probe/Example.java").apply {
            parentFile.mkdirs()
            writeText("package probe; public class Example {}\n")
        }
    }

    private class Nexus(project: File) : Closeable {
        val server = MemoryRepository(File(project, "http-evidence"))
        val states = ConcurrentHashMap<String, String>()
        private val sequence = AtomicInteger()
        init {
            server.response = { method, path ->
                when {
                    method == "POST" && path.endsWith("/start") -> {
                        val id = "created-${sequence.incrementAndGet()}"
                        states[id] = "open"
                        MemoryRepository.Response(201, data = """{"data":{"stagedRepositoryId":"$id"}}""")
                    }

                    method == "GET" && path.contains("/profile_repositories/") -> {
                        val id = "found-${sequence.incrementAndGet()}"
                        states[id] = "open"
                        MemoryRepository.Response(200, data = """{"data":[{"repositoryId":"$id","description":"selected find"}]}""")
                    }

                    method == "POST" && path.contains("/staging/bulk/") -> {
                        val body = Gson().fromJson(String(server.requests.last().third, Charsets.UTF_8), Map::class.java)
                        val id = (((body["data"] as Map<*, *>)["stagedRepositoryIds"] as List<*>).single()).toString()
                        if (path.endsWith("/close") && states.replace(id, "open", "closed")) {
                            MemoryRepository.Response(201)
                        } else if (path.endsWith("/promote") && states.replace(id, "closed", "released")) {
                            MemoryRepository.Response(201)
                        } else {
                            MemoryRepository.Response(409)
                        }
                    }

                    method == "GET" && path.contains("/staging/repository/") -> {
                        val id = path.substringAfterLast('/')
                        val state = states[id]
                        when (state) {
                            null -> MemoryRepository.Response(404)
                            "released" -> MemoryRepository.Response(404, data = """{"repositoryId":"$id"}""")
                            else -> MemoryRepository.Response(200, data = """{"repositoryId":"$id","type":"$state","transitioning":false}""")
                        }
                    }

                    method == "PUT" && path.contains("/deployByRepositoryId/") -> if (states[path.substringAfter("/deployByRepositoryId/").substringBefore('/')] == "open") null else MemoryRepository.Response(409)

                    else -> null
                }
            }
        }
        override fun close() =
            server.close()
    }
    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-session-hooks/${UUID.randomUUID()}/consumer").apply { mkdirs() }
}
