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

/** The diagnostic resolves the normal public module and never enrolls publication/session work. */
internal class ProductionStagingProfileDiagnosticTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `diagnostic uses final root group and queries freshly even with configured profile`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            val calls = AtomicInteger()
            remote.response = { method, path ->
                if (method == "GET" && path == "/service/local/staging/profiles") MemoryRepository.Response(200, data = profiles("selected-${calls.incrementAndGet()}")) else null
            }
            configure(project, remote, configuredProfile = "configured")
            File(project, "build.gradle.kts").appendText(
                """
                tasks.named<NexusStagingProfileDiagnostic>("retrieveProbeStagingProfile").get()
                afterEvaluate { group = "org.example.module"; version = "1.0" }
                """.trimIndent() + "\n"
            )
            var before = 0
            ModernGradleBuild(project, false, runtime).storeAndReuse("retrieveProbeStagingProfile") { result ->
                result.assertExecutedTasks(":retrieveProbeStagingProfile")
                assertThat(result.output).contains("Received staging profile id: 'selected-${calls.get()}' for package org.example.module")
                assertThat(result.output).doesNotContain("Received staging profile id: 'configured'")
                assertThat(remote.requests.drop(before).map { it.first + " " + it.second }).containsExactly("GET /service/local/staging/profiles")
                before = remote.requests.size
            }
            assertThat(calls.get()).isEqualTo(2)
            assertThat(File(project, "build/nexus-publishing/workflows")).doesNotExist()
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `typed diagnostic override honors exact namespace boundaries without changing session policy`(runtime: ModernRuntime) {
        listOf("diagnostic.special.module" to "diagnostic", "org.example" to "namespace", "org.examplex" to "general", "" to "empty").forEach { (group, expected) ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                val created = AtomicInteger()
                remote.response = { method, path ->
                    when {
                        method == "GET" && path == "/service/local/staging/profiles" -> MemoryRepository.Response(200, data = profiles("namespace"))
                        method == "POST" && path == "/service/local/staging/profiles/ordinary/start" -> MemoryRepository.Response(201, data = """{"data":{"stagedRepositoryId":"created-${created.incrementAndGet()}"}}""")
                        else -> null
                    }
                }
                configure(project, remote)
                File(project, "build.gradle.kts").appendText(
                    """
                    tasks.withType<NexusStagingProfileDiagnostic>().configureEach {
                        check(repositoryAlias.get() == "probe")
                        packageGroup.set("$group")
                    }
                    """.trimIndent() + "\n"
                )
                val build = ModernGradleBuild(project, false, runtime)
                var before = 0
                build.storeAndReuse("retrieveProbeStagingProfile") { result ->
                    result.assertExecutedTasks(":retrieveProbeStagingProfile")
                    assertThat(result.output).contains("Received staging profile id: '$expected' for package $group")
                    assertThat(remote.requests.drop(before).map { it.first + " " + it.second }).containsExactly("GET /service/local/staging/profiles")
                    before = remote.requests.size
                }
                if (group == "diagnostic.special.module") {
                    // Separate deliberate public initialization proves the task override did not mutate root session policy.
                    build.storeAndReuse("initializeProbeStagingRepository") { result ->
                        val delta = remote.requests.drop(before)
                        assertThat(delta.map { it.first + " " + it.second }).containsExactly("GET /service/local/staging/profiles", "POST /service/local/staging/profiles/ordinary/start")
                        assertThat(result.tasks.none { it.path.contains("prepareNexus") || it.path.contains("exportNexus") || it.path.endsWith("_upload") }).isTrue()
                        assertThat(String(delta.last().third, Charsets.UTF_8)).contains("ordinary.group.module:profile-diagnostic:1.0")
                        before = remote.requests.size
                    }
                    assertThat(created.get()).isEqualTo(2)
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `diagnostic failure and direct skip never acquire publish or leak credentials`(runtime: ModernRuntime) {
        listOf("missing", "auth", "direct").forEach { mode ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                val username = "diagnostic-user-${UUID.randomUUID()}"
                val password = "diagnostic-password-${UUID.randomUUID()}"
                configure(project, remote, staging = mode != "direct", endpointPresent = mode != "direct", username = username, password = password)
                if (mode == "auth") remote.expect(username, "server-only-different-password")
                remote.response = { method, path ->
                    if (method == "GET" && path == "/service/local/staging/profiles") MemoryRepository.Response(200, data = """{"data":[{"name":"unrelated","id":"unrelated"}]}""") else null
                }
                val build = ModernGradleBuild(project, false, runtime)
                var before = 0
                if (mode == "direct") {
                    build.storeAndReuse("retrieveProbeStagingProfile") { result ->
                        assertThat(result.task(":retrieveProbeStagingProfile")!!.outcome).isEqualTo(TaskOutcome.SKIPPED)
                        assertThat(result.tasks.map { it.path }).containsExactly(":retrieveProbeStagingProfile")
                        assertThat(remote.requests).isEmpty()
                    }
                    build.storeAndReuse("tasks") { result ->
                        assertThat(result.output).contains("Publishing tasks", "retrieveProbeStagingProfile - Gets and displays the staging profile ID")
                        assertThat(remote.requests).isEmpty()
                    }
                } else {
                    repeat(2) { index ->
                        val result = build.failWithDiagnostic(if (mode == "auth") "NEXUS_AUTHENTICATION_REJECTED" else "NEXUS_STAGING_PROFILE_NOT_FOUND", "retrieveProbeStagingProfile")
                        assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                        assertThat(result.output.contains(username) || result.output.contains(password)).describedAs("diagnostic errors exclude credentials").isFalse()
                        assertThat(result.tasks.map { it.path }).containsExactly(":retrieveProbeStagingProfile")
                        assertThat(remote.requests.drop(before).map { it.first + " " + it.second }).containsExactly("GET /service/local/staging/profiles")
                        before = remote.requests.size
                    }
                }
            }
        }
    }

    private fun profiles(namespace: String) =
        """{"data":[{"name":"","id":"empty"},{"name":"org","id":"general"},{"name":"org.example","id":"$namespace"},{"name":"org.example.mod","id":"wrong-prefix"},{"name":"ordinary.group","id":"ordinary"},{"name":"diagnostic.special","id":"diagnostic"}]}"""

    private fun configure(project: File, remote: MemoryRepository, staging: Boolean? = null, configuredProfile: String? = null, endpointPresent: Boolean = true, username: String? = null, password: String? = null) {
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
            rootProject.name = "profile-diagnostic"
            include("child")
            nexusPublishing {
                ${if (staging == null) "" else "useStaging.set($staging)"}
                repositories {
                    create("probe") {
                        ${if (endpointPresent) "nexusUrl.set(java.net.URI.create(\"${remote.endpoint}service/local/\"))" else ""}
                        ${if (configuredProfile == null) "" else "stagingProfileId.set(\"$configuredProfile\")"}
                        ${if (username == null) "" else "username.set(\"$username\")"}
                        ${if (password == null) "" else "password.set(\"$password\")"}
                        allowInsecureProtocol.set(true)
                    }
                }
            }
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle.kts").writeText(
            """
            import io.github.gradlenexus.publishplugin.tasks.NexusStagingProfileDiagnostic
            group = "ordinary.group.module"
            version = "1.0"
            check(tasks.withType<NexusStagingProfileDiagnostic>().names == setOf("retrieveProbeStagingProfile"))
            """.trimIndent() + "\n"
        )
        File(project, "child/build.gradle.kts").apply {
            parentFile.mkdirs()
            writeText("plugins { `java-library`; `maven-publish` }\n")
        }
    }

    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-profile-diagnostic/${UUID.randomUUID()}/consumer").apply { mkdirs() }
}
