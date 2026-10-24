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
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID

/** Actual root task actions and admission, distinct from transport-only JUnit callbacks. */
internal class ProductionWorkflowTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `direct root task publishes only selected child with fresh cache hit and credential convention`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote.endpoint.toString())
            val username = "owned-user-" + UUID.randomUUID()
            val password = "owned-password-" + UUID.randomUUID()
            remote.expect(username, password)
            File(project, "gradle.properties").writeText("probeUsername=$username\nprobePassword=$password\n")
            val runner = ModernGradleBuild(project, false, runtime)
            runner.storeAndReuse("help", ":child:prepareNexusMavenBundle") { result ->
                assertThat(remote.requests).isEmpty()
                assertThat(result.output).doesNotContain("NEXUS-WORKFLOW-RESET", "NEXUS-WORKFLOW-UPLOADED")
            }
            val invocations = mutableSetOf<String>()
            var before = 0
            runner.storeAndReuse(":child:publishToProbe") { result ->
                assertThat(result.output).contains("NEXUS-WORKFLOW-VALIDATED repository=probe units=[p1]", "NEXUS-WORKFLOW-UPLOADED repository=probe units=[p1]")
                    .doesNotContain("NEXUS-RESOLVE :other", "NEXUS-RESOLVE :empty", "NEXUS-RESOLVE :excluded")
                assertThat(result.output.contains(username) || result.output.contains(password)).describedAs("root credentials absent from logs").isFalse()
                assertThat(result.tasks.none { it.path.startsWith(":other:") || it.path.startsWith(":empty:") || it.path.startsWith(":excluded:") }).isTrue()
                val rootReset = result.output.indexOf("NEXUS-WORKFLOW-RESET ")
                val producerReset = result.output.indexOf("NEXUS-RESET :child:resetNexusMavenBundle")
                assertThat(rootReset).isGreaterThanOrEqualTo(0)
                assertThat(producerReset).isGreaterThan(rootReset)
                val requests = remote.requests.drop(before)
                assertThat(requests.any { it.first == "PUT" }).isTrue()
                assertThat(requests.all { it.second.startsWith("/direct/probe/child/") || (it.first == "GET" && it.second == "/direct/.meta/prefixes.txt") }).isTrue()
                assertThat(remote.authenticated.drop(before)).isNotEmpty().containsOnly(true)
                assertThat(remote.files["/direct/probe/child/1.0/child-1.0.jar"]).isEqualTo(File(project, "child/build/libs/child-1.0.jar").readBytes())
                val record = File(project, "build/nexus-publishing/workflows").listFiles()!!.single().resolve("upload.json").readText()
                listOf("plan.json", "session.json", "upload.json").forEach { name ->
                    val contents = File(project, "build/nexus-publishing/workflows").listFiles()!!.single().resolve(name).readText()
                    assertThat(contents.contains(username) || contents.contains(password)).describedAs("root credentials absent from $name").isFalse()
                }
                invocations.add(Regex("\"invocation\":\"([^\"]+)\"").find(record)!!.groupValues[1])
                before = remote.requests.size
            }
            assertThat(invocations).hasSize(2)
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `warm combined producer skips never authorize old files`(runtime: ModernRuntime) {
        listOf("export", "prepare").forEach { component ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                configure(project, remote.endpoint.toString())
                val path = if (component == "export") "child" else "empty"
                val runner = ModernGradleBuild(project, false, runtime)
                runner.storeAndReuse(":$path:publishToProbe")
                val before = remote.requests.size
                val manifest = File(project, "$path/build/nexus-publishing/bundles/maven/manifest.json")
                assertThat(manifest).isFile()
                if (component == "export") source(File(project, path), 2)
                File(project, "$path/build.gradle").appendText(
                    """
                    tasks.named('resetNexusMavenBundle') { enabled = false }
                    tasks.named('${if (component == "export") "exportNexusMavenMain" else "prepareNexusMavenBundle"}') { enabled = false }
                    """.trimIndent() + "\n"
                )
                repeat(2) { index ->
                    val result = runner.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL :$path:resetNexusMavenBundle", ":$path:publishToProbe")
                    assertCacheRefresh(result, index)
                    assertThat(result.output)
                        .doesNotContain("NEXUS-WORKFLOW-UPLOADED")
                    assertThat(result.task(":$path:resetNexusMavenBundle")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
                    assertThat(remote.requests.size).isEqualTo(before)
                    assertThat(manifest).isFile()
                    assertThat(workspace(project).resolve("session.json")).doesNotExist()
                    assertThat(workspace(project).resolve("upload.json")).doesNotExist()
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `export failure after native success witness blocks preparation and upload`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote.endpoint.toString())
            val runner = ModernGradleBuild(project, false, runtime)
            runner.storeAndReuse(":child:publishToProbe")
            val before = remote.requests.size
            File(project, "child/build.gradle").appendText("\ntasks.named('exportNexusMavenMain') { doLast { throw new GradleException('OWNED_NATIVE_AFTER_WITNESS_FAILURE') } }\n")
            repeat(2) { index ->
                val result = runner.failContinuingWithDiagnostic("OWNED_NATIVE_AFTER_WITNESS_FAILURE", ":child:publishToProbe")
                assertThat(result.output).contains("NEXUS-NATIVE-EXPORTED :child:exportNexusMavenMain", "NEXUS_WORKFLOW_NOT_SUCCESSFUL")
                    .contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                    .doesNotContain("NEXUS-WORKFLOW-UPLOADED")
                assertThat(result.task(":child:exportNexusMavenMain")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.FAILED)
                assertThat(result.task(":child:prepareNexusMavenBundle")).isNull()
                assertThat(remote.requests.size).isEqualTo(before)
                assertThat(workspace(project).resolve("upload.json")).doesNotExist()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `root reset and preparation skips fail closed with stale warm state`(runtime: ModernRuntime) {
        listOf("resetNexusMavenBundle", "prepareNexusMavenBundle", "root-reset").forEach { name ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                configure(project, remote.endpoint.toString())
                val runner = ModernGradleBuild(project, false, runtime)
                runner.storeAndReuse(":empty:publishToProbe")
                val before = remote.requests.size
                val skipped = if (name == "root-reset") ":" + workspace(project).name + "_reset" else ":empty:$name"
                val script = if (name == "root-reset") File(project, "build.gradle") else File(project, "empty/build.gradle")
                script.appendText("\ntasks.named('${skipped.substringAfterLast(':')}') { onlyIf { false } }\n")
                repeat(2) { index ->
                    val result = runner.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL", ":empty:publishToProbe")
                    assertCacheRefresh(result, index)
                    assertThat(result.task(skipped)!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
                    assertThat(result.output).doesNotContain("NEXUS-WORKFLOW-UPLOADED")
                    assertThat(remote.requests.size).isEqualTo(before)
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `local preparation does not enroll a unit while scoped union remains fresh with build cache`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote.endpoint.toString())
            File(project, "other/build.gradle").appendText(
                """
                tasks.named('resetNexusMavenBundle') { outputs.file(layout.buildDirectory.file('reset-cache-marker')); outputs.cacheIf { true } }
                tasks.named('prepareNexusMavenBundle') { outputs.cacheIf { true } }
                """.trimIndent() + "\n"
            )
            File(project, "build.gradle").appendText(
                """
                tasks.withType(io.github.gradlenexus.publishplugin.internal.modern.workflow.WorkflowTask).configureEach {
                    outputs.file(layout.buildDirectory.file('user-output-' + name))
                    outputs.cacheIf { true }
                }
                """.trimIndent() + "\n"
            )
            val runner = ModernGradleBuild(project, false, runtime)
            val ids = mutableSetOf<String>()
            var before = 0
            repeat(2) { index ->
                val result = runner.runWithBuildCache(":child:prepareNexusMavenBundle", ":other:publishToProbe")
                assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                assertThat(result.output)
                    .contains("NEXUS-WORKFLOW-VALIDATED repository=probe units=[p4]")
                    .doesNotContain("NEXUS-RESOLVE :child", "NEXUS-WORKFLOW-STAGED :child", "NEXUS-WORKFLOW-INTENT p1")
                assertThat(File(project, "child/build/nexus-publishing/bundles/maven/manifest.json")).isFile()
                val resetIndex = result.output.indexOf("NEXUS-WORKFLOW-RESET ")
                assertThat(resetIndex).isGreaterThanOrEqualTo(0)
                listOf("child", "other").forEach { path ->
                    assertThat(result.output.indexOf("NEXUS-RESET :$path:resetNexusMavenBundle")).isGreaterThan(resetIndex)
                }
                result.tasks.filter { it.path.endsWith("_upload") || it.path.endsWith("_reset") || it.path.endsWith("_session") || it.path.endsWith("_validate") || it.path == ":other:resetNexusMavenBundle" || it.path == ":other:prepareNexusMavenBundle" }.forEach {
                    assertThat(it.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                }
                assertThat(remote.requests.drop(before).filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/direct/probe/other/") }
                val record = workspace(project).resolve("upload.json").readText()
                ids.add(Regex("\"invocation\":\"([^\"]+)\"").find(record)!!.groupValues[1])
                before = remote.requests.size
            }
            assertThat(ids).hasSize(2)
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `root own scope aggregate aliases empty and exclusions compose without cycles`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote.endpoint.toString())
            File(project, "build.gradle").appendText(
                """
                apply plugin: 'maven-publish'
                publishing.publications { rootMetadata(MavenPublication) { groupId = 'probe'; artifactId = 'root-meta'; version = '1.0' } }
                """.trimIndent() + "\n"
            )
            val runner = ModernGradleBuild(project, false, runtime)
            var before = 0
            runner.storeAndReuse(":publishToProbe") { result ->
                assertThat(result.output).contains("NEXUS-WORKFLOW-VALIDATED repository=probe units=[p0]").doesNotContain("NEXUS-RESOLVE :child", "NEXUS-PREPARED :child")
                assertThat(remote.requests.drop(before).filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/direct/probe/root-meta/") }
                before = remote.requests.size
            }
            runner.storeAndReuse(":excluded:publishToProbe") { result ->
                assertThat(result.output).contains("NEXUS-EXCLUDED :excluded maven").doesNotContain("NEXUS-WORKFLOW-RESET", "NEXUS-RESOLVE", "NEXUS-PREPARED")
                assertThat(remote.requests.size).isEqualTo(before)
            }
            runner.storeAndReuse(":publishAllProjectsToProbe", ":child:publishToProbe") { result ->
                assertThat(result.output).contains("NEXUS-WORKFLOW-VALIDATED repository=probe units=[p0, p1, p2, p4]")
                    .doesNotContain("NEXUS-PREPARED :excluded", "NEXUS-RESOLVE :excluded")
                assertThat(result.tasks.count { it.path.endsWith("_upload") }).isEqualTo(1)
                val jars = remote.requests.drop(before).filter { it.first == "PUT" && it.second.endsWith(".jar") }
                assertThat(jars.map { it.second }).containsExactlyInAnyOrder("/direct/probe/child/1.0/child-1.0.jar", "/direct/probe/other/1.0/other-1.0.jar")
                before = remote.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `staging route without configured endpoint fails before any network work`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote.endpoint.toString())
            File(project, "settings.gradle").appendText("\nnexusPublishing.useStaging.set(true)\n")
            val runner = ModernGradleBuild(project, false, runtime)
            repeat(2) { index ->
                val result = runner.failWithDiagnostic("NEXUS_REPOSITORY_ENDPOINT_REQUIRED", ":child:publishToProbe")
                assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                assertThat(remote.requests).isEmpty()
                assertThat(workspace(project).resolve("session.json")).doesNotExist()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `overridden included build names isolate exact producer observations`(runtime: ModernRuntime) {
        val outer = directory()
        val inner = File(outer, "included").apply { mkdirs() }
        MemoryRepository(File(outer, "http-evidence")).use { remote ->
            configure(outer, remote.endpoint.toString() + "outer/")
            configure(inner, remote.endpoint.toString() + "inner/")
            File(inner, "settings.gradle").appendText("\nrootProject.name = 'different-inner-name'\n")
            File(outer, "settings.gradle").appendText("\nincludeBuild('included') { name = 'renamed-inclusion' }\n")
            var before = 0
            ModernGradleBuild(outer, false, runtime).storeAndReuse(":child:publishToProbe", ":renamed-inclusion:other:publishToProbe") { result ->
                assertThat(result.output).contains("NEXUS-WORKFLOW-VALIDATED repository=probe units=[p1]", "NEXUS-WORKFLOW-VALIDATED repository=probe units=[p4]")
                val writes = remote.requests.drop(before).filter { it.first == "PUT" }
                assertThat(writes.filter { it.second.startsWith("/outer/direct/") }).isNotEmpty().allMatch { it.second.startsWith("/outer/direct/probe/child/") }
                assertThat(writes.filter { it.second.startsWith("/inner/direct/") }).isNotEmpty().allMatch { it.second.startsWith("/inner/direct/probe/other/") }
                assertThat(writes.all { it.second.startsWith("/outer/direct/probe/child/") || it.second.startsWith("/inner/direct/probe/other/") }).isTrue()
                assertThat(result.tasks.count { it.path.endsWith("_upload") }).isEqualTo(2)
                assertThat(result.tasks.none { it.path.startsWith(":other:") || it.path.startsWith(":renamed-inclusion:child:") }).isTrue()
                before = remote.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `same producer path in included build cannot borrow outer successful outcomes`(runtime: ModernRuntime) {
        listOf("skips", "failed-export").forEach { mode ->
            val outer = directory()
            val inner = File(outer, "included").apply { mkdirs() }
            MemoryRepository(File(outer, "http-evidence")).use { remote ->
                configure(outer, remote.endpoint.toString() + "outer/")
                configure(inner, remote.endpoint.toString() + "inner/")
                File(inner, "settings.gradle").appendText("\nrootProject.name = 'different-inner-name'\n")
                File(outer, "settings.gradle").appendText("\nincludeBuild('included') { name = 'renamed-inclusion' }\n")
                val runner = ModernGradleBuild(outer, false, runtime)
                runner.storeAndReuse(":child:publishToProbe", ":renamed-inclusion:child:publishToProbe")
                val oldManifest = File(inner, "child/build/nexus-publishing/bundles/maven/manifest.json").readBytes()
                val customization = if (mode == "skips") {
                    """
                    tasks.named('resetNexusMavenBundle') { enabled = false }
                    tasks.named('prepareNexusMavenBundle') { enabled = false }
                    tasks.named('exportNexusMavenMain') { enabled = false }
                    """.trimIndent()
                } else {
                    """
                    tasks.named('prepareNexusMavenBundle') {
                        exportedPayloads.setFrom([])
                        mustRunAfter('exportNexusMavenMain')
                    }
                    tasks.named('exportNexusMavenMain') {
                        doLast { throw new GradleException('OWNED_INCLUDED_EXPORT_AFTER_WITNESS_FAILURE') }
                    }
                    """.trimIndent()
                }
                File(inner, "child/build.gradle").appendText(customization + "\n")
                var before = remote.requests.size
                repeat(2) { index ->
                    val expected = if (mode == "skips") "resetNexusMavenBundle" else "exportNexusMavenMain"
                    val requests = mutableListOf(":child:publishToProbe", ":renamed-inclusion:child:publishToProbe")
                    if (mode != "skips") requests.add(":renamed-inclusion:child:exportNexusMavenMain")
                    val result = runner.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL :child:$expected", *requests.toTypedArray())
                    assertCacheRefresh(result, index)
                    assertThat(result.task(":child:prepareNexusMavenBundle")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                    val innerPrefix = ":renamed-inclusion:child:"
                    if (mode == "skips") {
                        listOf("resetNexusMavenBundle", "prepareNexusMavenBundle", "exportNexusMavenMain").forEach {
                            assertThat(result.task(innerPrefix + it)!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
                        }
                        assertThat(File(inner, "child/build/nexus-publishing/bundles/maven/manifest.json").readBytes()).isEqualTo(oldManifest)
                    } else {
                        assertThat(result.output).contains("OWNED_INCLUDED_EXPORT_AFTER_WITNESS_FAILURE", "NEXUS-NATIVE-EXPORTED :child:exportNexusMavenMain")
                        assertThat(result.task(innerPrefix + "exportNexusMavenMain")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.FAILED)
                        listOf("resetNexusMavenBundle", "prepareNexusMavenBundle").forEach {
                            assertThat(result.task(innerPrefix + it)!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                        }
                    }
                    assertThat(result.task(":renamed-inclusion:${workspace(inner).name}_preflight1")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                    val writes = remote.requests.drop(before).filter { it.first == "PUT" }
                    assertThat(writes).isNotEmpty().allMatch { it.second.startsWith("/outer/direct/probe/child/") }
                    assertThat(workspace(inner).resolve("session.json")).doesNotExist()
                    assertThat(workspace(inner).resolve("upload.json")).doesNotExist()
                    assertThat(workspace(outer).resolve("upload.json")).isFile()
                    before = remote.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `two repositories order shared producers after both resets and remain independent under continue`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "first-http")).use { first ->
            MemoryRepository(File(project, "second-http")).use { second ->
                configure(project, first.endpoint.toString())
                File(project, "settings.gradle").appendText(
                    """
                    nexusPublishing.repositories.create('second') {
                        snapshotRepositoryUrl.set(uri('${second.endpoint}second/'))
                        allowInsecureProtocol.set(true)
                    }
                    """.trimIndent() + "\n"
                )
                val runner = ModernGradleBuild(project, false, runtime)
                val ids = mutableSetOf<String>()
                var firstBefore = 0
                var secondBefore = 0
                runner.storeAndReuse(":child:publishToProbe", ":child:publishToSecond", ":other:publishToSecond") { result ->
                    assertThat(result.output).contains("NEXUS-WORKFLOW-VALIDATED repository=probe units=[p1]", "NEXUS-WORKFLOW-VALIDATED repository=second units=[p1, p4]")
                    val resetPositions = Regex("NEXUS-WORKFLOW-RESET :[^\\s]+").findAll(result.output).map { it.range.first }.toList()
                    assertThat(resetPositions).hasSize(2)
                    listOf("child", "other").forEach { path ->
                        assertThat(result.output.indexOf("NEXUS-RESET :$path:resetNexusMavenBundle")).isGreaterThan(resetPositions.max()!!)
                    }
                    assertThat(result.tasks.count { it.path == ":child:prepareNexusMavenBundle" }).isEqualTo(1)
                    val firstWrites = first.requests.drop(firstBefore).filter { it.first == "PUT" }
                    val secondWrites = second.requests.drop(secondBefore).filter { it.first == "PUT" }
                    assertThat(firstWrites).isNotEmpty().allMatch { it.second.startsWith("/direct/probe/child/") }
                    assertThat(secondWrites).isNotEmpty().allMatch { it.second.startsWith("/second/probe/child/") || it.second.startsWith("/second/probe/other/") }
                    val directories = File(project, "build/nexus-publishing/workflows").listFiles()!!
                    assertThat(directories).hasSize(2)
                    directories.forEach { directory ->
                        val record = directory.resolve("upload.json").readText()
                        ids.add(Regex("\"invocation\":\"([^\"]+)\"").find(record)!!.groupValues[1])
                    }
                    firstBefore = first.requests.size
                    secondBefore = second.requests.size
                }
                assertThat(ids).hasSize(4)
                val firstDirectory = File(project, "build/nexus-publishing/workflows").listFiles()!!.single { it.resolve("upload.json").readText().contains("\"repository\":\"probe\"") }
                File(project, "build.gradle").appendText("\ntasks.named('${firstDirectory.name}_reset') { onlyIf { false } }\n")
                repeat(2) { index ->
                    val result = runner.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL :${firstDirectory.name}_reset", ":child:publishToProbe", ":child:publishToSecond", ":other:publishToSecond")
                    assertCacheRefresh(result, index)
                    assertThat(first.requests.size).isEqualTo(firstBefore)
                    assertThat(second.requests.drop(secondBefore).filter { it.first == "PUT" }).isNotEmpty()
                    val fresh = File(project, "build/nexus-publishing/workflows").listFiles()!!.single { it != firstDirectory }.resolve("upload.json").readText()
                    assertThat(fresh).contains("\"repository\":\"second\"")
                    secondBefore = second.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    @Tag("settings-smoke")
    fun `later validation action failure rejects a written receipt under continue`(runtime: ModernRuntime) {
        receiptAdmission(runtime, listOf("validate-after"))
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `written receipts and modified plan never override failed action admission`(runtime: ModernRuntime) {
        receiptAdmission(runtime, listOf("session-after", "changed-plan"))
    }

    private fun receiptAdmission(runtime: ModernRuntime, modes: List<String>) {
        modes.forEach { mode ->
            val project = directory()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                configure(project, remote.endpoint.toString())
                val runner = ModernGradleBuild(project, false, runtime)
                runner.storeAndReuse(":child:publishToProbe")
                val scope = workspace(project).name
                val target = if (mode == "session-after") "session" else "validate"
                val action = if (mode == "changed-plan") {
                    """
                    def file = task.planRecord.get().asFile
                    assert file.text.contains('"empty":false')
                    file.text = file.text.replace('"empty":false', '"empty":true')
                    """.trimIndent()
                } else {
                    "throw new GradleException('OWNED_WORKFLOW_AFTER_RECEIPT_FAILURE')"
                }
                File(project, "build.gradle").appendText("\ntasks.named('${scope}_$target') { doLast { task -> $action } }\n")
                val before = remote.requests.size
                repeat(2) { index ->
                    val diagnostic = if (mode == "changed-plan") "NEXUS_VALIDATED_PLAN_CHANGED" else "OWNED_WORKFLOW_AFTER_RECEIPT_FAILURE"
                    val result = runner.failContinuingWithDiagnostic(diagnostic, ":child:publishToProbe")
                    assertCacheRefresh(result, index)
                    val directory = workspace(project)
                    assertThat(directory.resolve("validated.json")).isFile()
                    assertThat(directory.resolve("upload.json")).doesNotExist()
                    assertThat(remote.requests.size).isEqualTo(before)
                    if (mode == "changed-plan") {
                        assertThat(result.task(":${scope}_validate")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                        assertThat(result.task(":${scope}_session")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.FAILED)
                        assertThat(directory.resolve("plan.json").readText()).contains("\"empty\":true")
                        assertThat(directory.resolve("session.json")).doesNotExist()
                    } else {
                        assertThat(result.task(":${scope}_$target")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.FAILED)
                        if (mode == "session-after") {
                            assertThat(directory.resolve("session.json")).isFile()
                        } else {
                            assertThat(directory.resolve("session.json")).doesNotExist()
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `replaced preparation dependencies must not admit an explicitly failed native exporter`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote.endpoint.toString())
            val runner = ModernGradleBuild(project, false, runtime)
            runner.storeAndReuse(":child:publishToProbe")
            File(project, "child/build.gradle").appendText(
                """
                tasks.named('prepareNexusMavenBundle') {
                    exportedPayloads.setFrom([])
                    mustRunAfter('exportNexusMavenMain')
                }
                tasks.named('exportNexusMavenMain') {
                    doLast { throw new GradleException('OWNED_BYPASSED_EXPORT_AFTER_WITNESS_FAILURE') }
                }
                """.trimIndent() + "\n"
            )
            var before = remote.requests.size
            repeat(2) { index ->
                val result = runner.failContinuingWithDiagnostic("OWNED_BYPASSED_EXPORT_AFTER_WITNESS_FAILURE", ":child:exportNexusMavenMain", ":child:publishToProbe")
                assertCacheRefresh(result, index)
                assertThat(result.output).contains("NEXUS-NATIVE-EXPORTED :child:exportNexusMavenMain", "NEXUS_WORKFLOW_NOT_SUCCESSFUL :child:exportNexusMavenMain").doesNotContain("NEXUS-WORKFLOW-UPLOADED")
                assertThat(result.task(":child:exportNexusMavenMain")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.FAILED)
                assertThat(result.task(":child:resetNexusMavenBundle")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                assertThat(result.task(":child:prepareNexusMavenBundle")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                // Retain both strict invocations before asserting the contract, so failure-path reuse is observable.
                File(project, "bypass-$index-summary.txt").writeText("freshPUTs=" + remote.requests.drop(before).count { it.first == "PUT" } + "\n" + result.output)
                before = remote.requests.size
            }
            assertThat(File(project, "bypass-0-summary.txt").readText()).contains("freshPUTs=0\n")
            assertThat(File(project, "bypass-1-summary.txt").readText()).contains("freshPUTs=0\n")
            assertThat(workspace(project).resolve("upload.json")).doesNotExist()
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `manifest names drive late removed metadata-only and empty publication admission`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            configure(project, remote.endpoint.toString())
            val runner = ModernGradleBuild(project, false, runtime)
            runner.storeAndReuse(":child:publishToProbe")
            val childScript = File(project, "child/build.gradle")
            childScript.appendText(
                """
                afterEvaluate {
                    publishing.publications.create('late', MavenPublication) {
                        groupId = 'probe'; artifactId = 'late-meta'; version = '1.0'
                    }
                }
                """.trimIndent() + "\n"
            )
            var before = remote.requests.size
            for (phase in listOf("late", "removed", "empty")) {
                if (phase == "removed") childScript.appendText("\nafterEvaluate { publishing.publications.remove(publishing.publications.getByName('main')) }\n")
                if (phase == "empty") childScript.appendText("\nafterEvaluate { publishing.publications.remove(publishing.publications.getByName('late')) }\n")
                repeat(2) { index ->
                    val result = runner.run(":child:publishToProbe")
                    assertCacheRefresh(result, index)
                    assertThat(result.output).contains("NEXUS-WORKFLOW-VALIDATED repository=probe units=[p1]")
                    val writes = remote.requests.drop(before).filter { it.first == "PUT" }
                    when (phase) {
                        "late" -> {
                            assertThat(result.task(":child:exportNexusMavenMain")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                            assertThat(result.task(":child:exportNexusMavenLate")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                            assertThat(writes.any { it.second.endsWith("/late-meta-1.0.pom") }).isTrue()
                            assertThat(writes.any { it.second.endsWith("/child-1.0.jar") }).isTrue()
                        }

                        "removed" -> {
                            assertThat(result.task(":child:exportNexusMavenMain")).isNull()
                            assertThat(result.task(":child:exportNexusMavenLate")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                            assertThat(writes).isNotEmpty().allMatch { it.second.startsWith("/direct/probe/late-meta/") }
                        }

                        else -> {
                            assertThat(result.task(":child:exportNexusMavenMain")).isNull()
                            assertThat(result.task(":child:exportNexusMavenLate")).isNull()
                            assertThat(result.output).contains("empty=true")
                            assertThat(remote.requests.size).isEqualTo(before)
                        }
                    }
                    assertThat(result.tasks.none { it.path.startsWith(":other:") || it.path.startsWith(":empty:") || it.path.startsWith(":excluded:") }).isTrue()
                    before = remote.requests.size
                }
            }
        }
    }

    private fun assertCacheRefresh(result: org.gradle.testkit.runner.BuildResult, index: Int) {
        if (index == 0) {
            assertThat(result.output.contains("Configuration cache entry stored.") || result.output.contains("Configuration cache entry updated for no projects, no projects up-to-date.")).describedAs("strict cache entry stored or incrementally updated").isTrue()
        } else {
            assertThat(result.output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
        }
    }

    private fun workspace(project: File): File =
        File(project, "build/nexus-publishing/workflows").listFiles()!!.single()

    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-workflow/${UUID.randomUUID()}/consumer").apply { mkdirs() }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `tracked endpoint input redirects uploads after cache reuse without changing scripts`(runtime: ModernRuntime) {
        val project = directory()
        MemoryRepository(File(project, "first-evidence")).use { first ->
            MemoryRepository(File(project, "second-evidence")).use { second ->
                configure(project, first.endpoint.toString())
                val script = File(project, "settings.gradle").readText()
                val build = ModernGradleBuild(project, false, runtime)
                build.storeAndReuse(":child:publishToProbe") {
                    assertThat(first.requests.filter { it.first == "PUT" }).isNotEmpty()
                    assertThat(second.requests).isEmpty()
                }
                val before = first.requests.size
                File(project, "fixture-endpoint.txt").writeText(second.endpoint.toString())
                build.storeAndReuse(":child:publishToProbe") {
                    assertThat(first.requests).hasSize(before)
                    assertThat(second.requests.filter { it.first == "PUT" }).isNotEmpty()
                    assertThat(second.files["/direct/probe/child/1.0/child-1.0.jar"]).isEqualTo(File(project, "child/build/libs/child-1.0.jar").readBytes())
                }
                assertThat(File(project, "settings.gradle").readText()).isEqualTo(script)
            }
        }
    }

    private fun configure(project: File, endpoint: String) {
        File(project, "fixture-endpoint.txt").writeText(endpoint.toString())
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            def fixtureEndpoint = providers.fileContents(layout.settingsDirectory.file('fixture-endpoint.txt')).asText.get()
            rootProject.name = 'workflow'
            include 'child', 'empty', 'excluded', 'other'
            nexusPublishing.useStaging.set(false)
            nexusPublishing.excludedProjects.add(':excluded')
            nexusPublishing.repositories.create('probe') {
                snapshotRepositoryUrl.set(uri(fixtureEndpoint + 'direct/'))
                allowInsecureProtocol.set(true)
            }
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle").writeText("group = 'root.group'; version = '1.0'\n")
        listOf("child", "other").forEach { path ->
            val child = File(project, path).apply { mkdirs() }
            File(child, "build.gradle").writeText(
                """
                plugins { id 'java-library'; id 'maven-publish' }
                group = 'probe'; version = '1.0'
                publishing.publications { main(MavenPublication) { from components.java; artifactId = '$path' } }
                """.trimIndent() + "\n"
            )
            source(child)
        }
        listOf("empty", "excluded").forEach { path ->
            File(project, path).mkdirs()
            File(project, "$path/build.gradle").writeText("")
        }
    }
    private fun source(project: File, value: Int = 1) {
        File(project, "src/main/java/probe/Example.java").apply {
            parentFile.mkdirs()
            writeText("package probe; public class Example { public static int value() { return $value; } }\n")
        }
    }
}
