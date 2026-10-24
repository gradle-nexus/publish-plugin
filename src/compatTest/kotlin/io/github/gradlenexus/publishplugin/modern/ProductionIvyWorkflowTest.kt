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

/** Actual Ivy root dataflow, immutable policy and streamed transport over packaged production code. */
internal class ProductionIvyWorkflowTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `direct root own alias and aggregate collect only matching Ivy producers including empty`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model, false, rootPublication = true)
            val build = ModernGradleBuild(project, false, runtime)
            var before = 0
            build.storeAndReuse(":publishToProbe") { result ->
                noMaven(result)
                assertThat(result.output).doesNotContain("NEXUS-RESOLVE :child ivy", "NEXUS-RESOLVE :other ivy", "NEXUS-RESOLVE :empty ivy")
                assertThat(result.tasks.none { it.path.startsWith(":child:") || it.path.startsWith(":other:") || it.path.startsWith(":empty:") }).isTrue()
                val delta = model.server.requests.drop(before)
                assertThat(delta.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/direct/probe/root/1.0/") }
                assertThat(model.server.files["/direct/probe/root/1.0/root-1.0.jar"]).isEqualTo(File(project, "build/libs/root-1.0.jar").readBytes())
                policy(record(project, "upload.json"), false)
                before = model.server.requests.size
            }
            var aggregateInvocation = 0
            build.storeAndReuse(":publishAllProjectsToProbe") { result ->
                noMaven(result)
                if (aggregateInvocation++ == 0) {
                    assertThat(result.output).contains("NEXUS-RESOLVE :child ivy", "NEXUS-RESOLVE :other ivy", "NEXUS-RESOLVE :empty ivy")
                } else {
                    assertThat(result.output).doesNotContain("NEXUS-RESOLVE")
                }
                listOf("root", "child", "other").forEach { name ->
                    val jar = File(project, if (name == "root") "build/libs/root-1.0.jar" else "$name/build/libs/$name-1.0.jar")
                    assertThat(model.server.files["/direct/probe/$name/1.0/$name-1.0.jar"]).isEqualTo(jar.readBytes())
                }
                val delta = model.server.requests.drop(before)
                assertThat(delta.none { it.first == "POST" }).isTrue()
                assertThat(delta.filter { it.first == "PUT" }.map { it.second }).doesNotHaveDuplicates()
                assertThat(record(project, "plan.json")["units"]).isEqualTo(listOf("p0", "p1", "p2", "p3"))
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    @Tag("settings-smoke")
    fun `staged child Ivy upload and lifecycle use fresh format bound session on cache hit`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model, true)
            var before = 0
            val invocations = mutableSetOf<String>()
            ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository") { result ->
                noMaven(result)
                assertThat(result.tasks.none { it.path.startsWith(":other:") || it.path.startsWith(":empty:") }).isTrue()
                val delta = model.server.requests.drop(before)
                assertThat(delta.filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/profiles/profile/start", "/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                val id = "created-${model.created.get()}"
                assertThat(model.server.files["/service/local/staging/deployByRepositoryId/$id/probe/child/1.0/child-1.0.jar"]).isEqualTo(File(project, "child/build/libs/child-1.0.jar").readBytes())
                assertThat(delta.indexOfFirst { it.first == "POST" && it.second.endsWith("/bulk/close") }).isGreaterThan(delta.indexOfLast { it.first == "PUT" })
                val receipt = record(project, "release.json")
                policy(receipt, false)
                assertThat(receipt["repositoryId"]).isEqualTo(id)
                assertThat(invocations.add(receipt["invocation"].toString())).isTrue()
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `standalone Ivy recovery binds selected layout and global flag without any plan`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model, true, customLayout = true)
            val build = ModernGradleBuild(project, false, runtime)
            var before = 0
            repeat(2) { index ->
                model.seed("recover")
                val result = build.runRecovery(StagingRecoveryTask(":closeProbeStagingRepository", "recover"), StagingRecoveryTask(":releaseProbeStagingRepository", "recover"))
                cache(result, index)
                assertThat(result.tasks.none { it.path.endsWith("_validate") || it.path.endsWith("_session") || it.path.endsWith("_upload") || it.path.contains("prepareNexus") || it.path.startsWith(":child:") }).isTrue()
                assertThat(File(workspace(project), "plan.json")).doesNotExist()
                assertThat(model.server.requests.drop(before).filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                val closed = record(project, "close.json")
                val released = record(project, "release.json")
                policy(closed, true)
                policy(released, true)
                assertThat(closed["layoutPolicy"]).isEqualTo(released["layoutPolicy"])
                assertThat(released["repositoryId"]).isEqualTo("recover")
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `changed duplicate validation policy cannot reach a client or session`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model, true)
            File(project, "build.gradle").appendText(
                """
                tasks.withType(io.github.gradlenexus.publishplugin.internal.modern.workflow.WorkflowValidate).configureEach {
                    doLast {
                        def file = validationReceipt.get().asFile
                        def value = new groovy.json.JsonSlurper().parse(file)
                        value.suppressIvyModuleMetadata = !value.suppressIvyModuleMetadata
                        file.setText(groovy.json.JsonOutput.toJson(value),'UTF-8')
                    }
                }
                """.trimIndent() + "\n"
            )
            val build = ModernGradleBuild(project, false, runtime)
            repeat(2) { index ->
                val result = build.failContinuingWithDiagnostic("NEXUS_VALIDATED_PLAN_CHANGED", ":child:publishToProbe")
                cache(result, index)
                assertThat(result.tasks.single { it.path.endsWith("_validate") }.outcome).isEqualTo(TaskOutcome.SUCCESS)
                assertThat(model.server.requests).isEmpty()
                assertThat(File(workspace(project), "session.json")).doesNotExist()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `same alias layout changes invalidate policy while ordinary direct transitions skip`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            val identities = mutableListOf<String>()
            var before = 0
            listOf(false, true, false).forEach { custom ->
                configure(project, model, false, customLayout = custom)
                val build = ModernGradleBuild(project, false, runtime)
                repeat(2) { index ->
                    val result = build.run(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                    refreshed(result, index)
                    noMaven(result)
                    assertThat(result.task(":closeProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SKIPPED)
                    assertThat(result.task(":releaseProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SKIPPED)
                    val value = record(project, "upload.json")
                    policy(value, custom)
                    if (index == 0) identities.add(value["layoutPolicy"].toString())
                    val delta = model.server.requests.drop(before)
                    assertThat(delta.none { it.first == "POST" }).isTrue()
                    val jarPath = if (custom) "/direct/probe/child/1.0/jar/child-1.0.jar" else "/direct/probe/child/1.0/child-1.0.jar"
                    assertThat(delta.filter { it.first == "PUT" }.map { it.second }).contains(jarPath).doesNotHaveDuplicates()
                    assertThat(model.server.files[jarPath]).isEqualTo(File(project, "child/build/libs/child-1.0.jar").readBytes())
                    val manifest = File(project, "child/build/nexus-publishing/bundles/ivy/manifest.json").readText()
                    assertThat(manifest.contains("gradle-module")).isEqualTo(!custom)
                    before = model.server.requests.size
                }
            }
            assertThat(identities[0]).isEqualTo(identities[2]).isNotEqualTo(identities[1])
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `warm skipped preparation and failed exporter witness cannot admit Ivy upload`(runtime: ModernRuntime) {
        listOf("skips", "failed-export").forEach { mode ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model, true)
                val build = ModernGradleBuild(project, false, runtime)
                build.storeAndReuse(":child:publishToProbe")
                val oldManifest = File(project, "child/build/nexus-publishing/bundles/ivy/manifest.json").readBytes()
                File(project, "child/build.gradle").appendText(
                    if (mode == "skips") {
                        "\ntasks.named('resetNexusIvyBundle') { enabled=false }; tasks.named('prepareNexusIvyBundle') { enabled=false }; tasks.named('exportNexusIvyIvy') { enabled=false }\n"
                    } else {
                        """
                        tasks.named('prepareNexusIvyBundle') { exportedPayloads.setFrom([]);mustRunAfter('exportNexusIvyIvy') }
                        tasks.named('exportNexusIvyIvy') { doLast { throw new GradleException('OWNED_IVY_EXPORT_AFTER_WITNESS_FAILURE') } }
                        """.trimIndent() + "\n"
                    }
                )
                val before = model.server.requests.size
                repeat(2) { index ->
                    val task = if (mode == "skips") "resetNexusIvyBundle" else "exportNexusIvyIvy"
                    val requested = mutableListOf(":child:publishToProbe")
                    if (mode != "skips") requested.add(":child:exportNexusIvyIvy")
                    val result = build.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL :child:$task", *requested.toTypedArray())
                    refreshed(result, index)
                    if (mode == "skips") {
                        assertThat(File(project, "child/build/nexus-publishing/bundles/ivy/manifest.json").readBytes()).isEqualTo(oldManifest)
                        assertThat(result.task(":child:prepareNexusIvyBundle")!!.outcome).isEqualTo(TaskOutcome.SKIPPED)
                    } else {
                        assertThat(result.output).contains("NEXUS-NATIVE-EXPORTED :child:exportNexusIvyIvy", "OWNED_IVY_EXPORT_AFTER_WITNESS_FAILURE")
                        assertThat(result.task(":child:prepareNexusIvyBundle")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                        assertThat(result.task(":child:exportNexusIvyIvy")!!.outcome).isEqualTo(TaskOutcome.FAILED)
                    }
                    assertThat(model.server.requests.size).isEqualTo(before)
                    assertThat(File(workspace(project), "session.json")).doesNotExist()
                    assertThat(File(workspace(project), "upload.json")).doesNotExist()
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `excluded and late metadata only participants update selected Ivy manifests on removal`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model, false)
            File(project, "settings.gradle").appendText("\nnexusPublishing.excludedProjects.add(':other')\n")
            File(project, "empty/build.gradle").writeText("plugins { id 'ivy-publish' };group='probe';version='1.0';publishing.publications { metadataOnly(IvyPublication) { module='metadata-only' } }\n")
            val late = "\nafterEvaluate { publishing.publications.create('late',IvyPublication) { module='late';organisation='probe';revision='1.0' } }\n"
            File(project, "child/build.gradle").appendText(late)
            val build = ModernGradleBuild(project, false, runtime)
            build.storeAndReuse(":other:publishToProbe") { result ->
                assertThat(result.output).contains("NEXUS-EXCLUDED :other ivy").doesNotContain("NEXUS-RESOLVE", "NEXUS-WORKFLOW-RESET", "NEXUS-PREPARED")
                assertThat(model.server.requests).isEmpty()
            }
            var before = 0
            build.storeAndReuse(":publishAllProjectsToProbe") { result ->
                noMaven(result)
                assertThat(result.tasks.none { it.path.startsWith(":other:") || it.path == ":empty:compileJava" }).isTrue()
                assertThat(result.task(":child:exportNexusIvyLate")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                val paths = model.server.requests.drop(before).filter { it.first == "PUT" }.map { it.second }
                assertThat(paths).contains("/direct/probe/late/1.0/ivy-1.0.xml", "/direct/probe/metadata-only/1.0/ivy-1.0.xml").doesNotHaveDuplicates()
                before = model.server.requests.size
            }
            File(project, "child/build.gradle").writeText(publicationScript("child"))
            repeat(2) { index ->
                val result = build.run(":publishAllProjectsToProbe")
                refreshed(result, index)
                assertThat(result.tasks.none { it.path == ":child:exportNexusIvyLate" }).isTrue()
                assertThat(File(project, "child/build/nexus-publishing/bundles/ivy/manifest.json").readText()).doesNotContain("\"name\":\"late\"")
                val paths = model.server.requests.drop(before).filter { it.first == "PUT" }.map { it.second }
                assertThat(paths).isNotEmpty().noneMatch { it.contains("/late/") || it.contains("/other/") }
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `distinct Maven and Ivy contexts isolate credentials selection and failed uploads`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { ivy ->
            NexusModel(File(project, "maven-model").apply { mkdirs() }).use { maven ->
                configure(project, ivy, true)
                ivy.server.expect("ivy-user", "ivy-password")
                maven.server.expect("maven-user", "maven-password")
                File(project, "settings.gradle").appendText(
                    """
                    nexusPublishing.repositories.named('probe') { username.set('ivy-user');password.set('ivy-password') }
                    nexusPublishing.repositories.create('central') {
                        nexusUrl.set(uri('${maven.server.endpoint}service/local'));snapshotRepositoryUrl.set(uri('${maven.server.endpoint}direct/'))
                        stagingProfileId.set('profile');allowInsecureProtocol.set(true);username.set('maven-user');password.set('maven-password')
                    }
                    """.trimIndent() + "\n"
                )
                val build = ModernGradleBuild(project, false, runtime)
                val tasks = arrayOf(":child:publishToProbe", ":other:publishToCentral", ":closeAndReleaseStagingRepositories")
                var ivyBefore = 0
                var mavenBefore = 0
                build.storeAndReuse(*tasks) { result ->
                    assertThat(result.tasks.none { (it.path.startsWith(":child:") && it.path.contains("Maven")) || (it.path.startsWith(":other:") && it.path.contains("Ivy")) }).isTrue()
                    listOf(ivy to ivyBefore, maven to mavenBefore).forEach { (model, before) ->
                        val delta = model.server.requests.drop(before)
                        assertThat(delta.filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/profiles/profile/start", "/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                        assertThat(delta.filter { it.first == "PUT" }).isNotEmpty()
                        assertThat(model.server.authenticated.drop(before)).allMatch { it }
                    }
                    assertThat(record(project, "release.json")["format"]).isEqualTo("ivy")
                    assertThat(record(project, "release.json", "central")["format"]).isEqualTo("maven")
                    assertThat(record(project, "release.json")["invocation"]).isNotEqualTo(record(project, "release.json", "central")["invocation"])
                    ivyBefore = ivy.server.requests.size
                    mavenBefore = maven.server.requests.size
                }
                ivy.failUploads = true
                repeat(2) { _ ->
                    val result = build.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL", *tasks)
                    assertThat(result.output).contains("Configuration cache entry reused.", "403")
                    val bad = ivy.server.requests.drop(ivyBefore)
                    val good = maven.server.requests.drop(mavenBefore)
                    assertThat(bad.filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/profiles/profile/start")
                    assertThat(bad.filter { it.first == "PUT" }).isNotEmpty()
                    assertThat(good.filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/profiles/profile/start", "/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                    assertThat(result.task(":releaseCentralStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                    assertThat(File(workspace(project), "close.json")).doesNotExist()
                    ivyBefore = ivy.server.requests.size
                    mavenBefore = maven.server.requests.size
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `changed collected Ivy payload after acquisition rejects upload and lifecycle`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model, true)
            val build = ModernGradleBuild(project, false, runtime)
            build.storeAndReuse(":child:publishToProbe")
            File(project, "build.gradle").appendText(
                """
                tasks.withType(io.github.gradlenexus.publishplugin.internal.modern.workflow.WorkflowUpload).configureEach {
                    doFirst { task ->
                        def directory=new File(task.staged.get().asFile,'p1')
                        def manifest=new groovy.json.JsonSlurper().parse(new File(directory,'manifest.json'))
                        def artifact=manifest.publications.collectMany { it.entries }.find { it.role=='artifact' }
                        def file=new File(directory,artifact.payload);def bytes=file.bytes;bytes[0]=(byte)(bytes[0]^1);file.bytes=bytes
                    }
                }
                """.trimIndent() + "\n"
            )
            var before = model.server.requests.size
            repeat(2) { index ->
                val result = build.failContinuingWithDiagnostic("NEXUS_INVALID_BUNDLE: digest mismatch", ":child:publishToProbe", ":closeAndReleaseStagingRepositories")
                cache(result, index)
                assertThat(result.tasks.single { it.path.endsWith("_session") }.outcome).isEqualTo(TaskOutcome.SUCCESS)
                assertThat(result.tasks.single { it.path.endsWith("_upload") }.outcome).isEqualTo(TaskOutcome.FAILED)
                assertThat(record(project, "session.json")["repositoryId"]).isEqualTo("created-${model.created.get()}")
                assertThat(model.server.requests.drop(before).map { it.first to it.second }).containsExactly("POST" to "/service/local/staging/profiles/profile/start")
                listOf("upload.json", "close.json", "release.json").forEach { assertThat(File(workspace(project), it)).doesNotExist() }
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `Ivy union collision invalid paths and metadata policy mismatch reject before acquisition`(runtime: ModernRuntime) {
        listOf("collision", "typed-ivy-default", "invalid-path", "metadata-policy").forEach { mode ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model, true, customLayout = mode !in listOf("collision", "typed-ivy-default"))
                val requested = mutableListOf(":child:publishToProbe")
                val diagnostic = when (mode) {
                    "collision" -> {
                        File(project, "other/build.gradle").appendText("\npublishing.publications.named('ivy') { module='child' }\n")
                        requested.add(":other:publishToProbe")
                        "NEXUS_DESTINATION_COLLISION"
                    }

                    "typed-ivy-default" -> {
                        File(project, "child/ordinary.txt").writeText("ordinary typed ivy artifact")
                        File(project, "child/build.gradle").appendText("\npublishing.publications.named('ivy') { artifact(file('ordinary.txt')) { name='ordinary';type='ivy';extension='module';classifier='custom' } }\n")
                        "NEXUS_DESTINATION_COLLISION"
                    }

                    "invalid-path" -> {
                        File(project, "settings.gradle").appendText("\nnexusPublishing.repositories.named('probe') { ivyPatternLayout { artifact('../[artifact](.[ext])');ivy('../ivy.xml') } }\n")
                        "NEXUS_INVALID_DESTINATION"
                    }

                    else -> {
                        File(project, "child/build.gradle").appendText("\ntasks.named('generateMetadataFileForIvyPublication') { enabled=true }\n")
                        "NEXUS_IVY_METADATA_POLICY_MISMATCH_BEFORE_DEPLOY"
                    }
                }
                val build = ModernGradleBuild(project, false, runtime)
                repeat(2) { index ->
                    val result = build.failContinuingWithDiagnostic(diagnostic, *requested.toTypedArray())
                    cache(result, index)
                    noMaven(result)
                    assertThat(result.task(":child:prepareNexusIvyBundle")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                    if (mode == "metadata-policy") {
                        assertThat(File(project, "child/build/nexus-publishing/bundles/ivy/manifest.json").readText()).contains("gradle-module")
                    }
                    assertThat(model.server.requests).isEmpty()
                    assertThat(File(workspace(project), "session.json")).doesNotExist()
                    assertThat(File(workspace(project), "upload.json")).doesNotExist()
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `Ivy public initialize find and configured resume bind adopted upload and lifecycle receipts`(runtime: ModernRuntime) {
        listOf("initialize", "find", "resume").forEach { mode ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model, true)
                File(project, "settings.gradle").appendText("\nnexusPublishing.repositoryDescription.set('ivy recovery')\n")
                if (mode == "resume") File(project, "settings.gradle").appendText("nexusPublishing.repositories.named('probe') { stagingRepositoryId.set('saved') }\n")
                model.seed(if (mode == "find") "found" else "saved")
                val tasks = mutableListOf(":child:publishToProbe", ":closeAndReleaseProbeStagingRepository")
                if (mode != "resume") tasks.add(":${mode}ProbeStagingRepository")
                var before = 0
                val invocations = mutableSetOf<String>()
                ModernGradleBuild(project, false, runtime).storeAndReuse(*tasks.toTypedArray()) { result ->
                    noMaven(result)
                    val id = when (mode) {
                        "initialize" -> "created-${model.created.get()}"
                        "find" -> "found"
                        else -> "saved"
                    }
                    val delta = model.server.requests.drop(before)
                    assertThat(delta.count { it.first == "POST" && it.second.endsWith("/start") }).isEqualTo(if (mode == "initialize") 1 else 0)
                    assertThat(delta.count { it.first == "GET" && it.second.endsWith("/profile_repositories/profile") }).isEqualTo(if (mode == "find") 1 else 0)
                    assertThat(delta.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.startsWith("/service/local/staging/deployByRepositoryId/$id/probe/child/") }
                    assertThat(delta.filter { it.first == "POST" && !it.second.endsWith("/start") }.map { it.second }).containsExactly("/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                    val session = record(project, "session.json")
                    policy(session, false)
                    assertThat(session["repositoryId"]).isEqualTo(id)
                    assertThat(invocations.add(session["invocation"].toString())).isTrue()
                    assertThat(record(project, "release.json")["planBinding"]).isEqualTo(session["planBinding"])
                    assertThat(record(project, "release.json")["repositoryId"]).isEqualTo(id)
                    if (mode != "resume") {
                        assertThat(result.task(":${mode}ProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                        policy(record(project, "$mode-session.json"), false)
                        assertThat(result.output).contains("NEXUS-WORKFLOW-ADOPTED repository=probe id=$id")
                    }
                    before = model.server.requests.size
                    if (mode != "initialize") model.seed(id)
                }
            }
        }
    }

    private fun refreshed(result: org.gradle.testkit.runner.BuildResult, index: Int) {
        if (index > 0) {
            assertThat(result.output).contains("Configuration cache entry reused.")
        } else {
            assertThat(result.output.contains("Configuration cache entry stored.") || result.output.contains("Configuration cache entry reused.") || Regex("Configuration cache entry updated for .*projects.*").containsMatchIn(result.output)).isTrue()
        }
    }

    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-ivy-workflow/${UUID.randomUUID()}/consumer").apply { mkdirs() }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `same named Ivy artifacts with distinct types survive production export and native upload`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model, false, customLayout = true)
            val control = File(project, "native-control").apply { mkdirs() }
            val producer = """
                plugins { id 'ivy-publish' }
                group='probe.tools';version='1.0'
                publishing.publications {
                    ivy(IvyPublication) {
                        module='typed'
                        artifact(file('alpha.txt')) { name='shared';classifier='same';extension='txt';type='alpha' }
                        artifact(file('beta.txt')) { name='shared';classifier='same';extension='txt';type='beta' }
                    }
                }
            """.trimIndent() + "\n"
            listOf(control, File(project, "child")).forEach { directory ->
                File(directory, "build.gradle").writeText(producer)
                File(directory, "alpha.txt").writeText("alpha bytes\n")
                File(directory, "beta.txt").writeText("beta bytes\n")
            }
            File(control, "settings.gradle").writeText("rootProject.name='native'\n")
            File(control, "build.gradle").appendText("publishing.repositories { ivy { name='control';url=layout.buildDirectory.dir('repository');patternLayout { artifact '[organisation]/[module]/[revision]/[type]/[artifact]-[revision](-[classifier])(.[ext])';ivy '[organisation]/[module]/[revision]/[type]/[artifact]-[revision](.[ext])';m2compatible=true } } }\n")
            ModernGradleBuild(control, false, runtime).storeAndReuse("publishIvyPublicationToControlRepository")
            var before = 0
            ModernGradleBuild(project, false, runtime).storeAndReuse(":child:publishToProbe") { result ->
                noMaven(result)
                val writes = model.server.requests.drop(before).filter { it.first == "PUT" }.map { it.second }
                assertThat(writes).doesNotHaveDuplicates()
                listOf("alpha", "beta").forEach { type ->
                    val path = "probe/tools/typed/1.0/$type/shared-1.0-same.txt"
                    assertThat(model.server.files["/direct/$path"]).isEqualTo(File(control, "build/repository/$path").readBytes())
                    assertThat(model.server.files["/direct/$path"]!!.toString(Charsets.UTF_8)).isEqualTo("$type bytes\n")
                }
                before = model.server.requests.size
            }
        }
    }

    private fun workspace(project: File, repository: String = "probe"): File {
        val identity = project.toPath().toAbsolutePath().normalize().toString() + "\u0000" + repository
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(project, "build/nexus-publishing/workflows/nexus_$hash")
    }
    private fun record(project: File, name: String, repository: String = "probe"): Map<*, *> =
        Gson().fromJson(File(workspace(project, repository), name).readText(), Map::class.java)
    private fun policy(record: Map<*, *>, suppression: Boolean) {
        assertThat(record["format"]).isEqualTo("ivy")
        assertThat(record["layoutPolicy"].toString()).startsWith("ivy:")
        assertThat(record["suppressIvyModuleMetadata"]).isEqualTo(suppression)
    }
    private fun noMaven(result: org.gradle.testkit.runner.BuildResult) {
        assertThat(result.tasks.none { it.path.contains("Maven") }).isTrue()
        assertThat(Regex("NEXUS-RESOLVE .* maven").containsMatchIn(result.output)).isFalse()
    }
    private fun cache(result: org.gradle.testkit.runner.BuildResult, index: Int) {
        assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
    }
    private fun configure(project: File, model: NexusModel, staging: Boolean, rootPublication: Boolean = false, customLayout: Boolean = false) {
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            rootProject.name='root'
            include 'child','other','empty'
            nexusPublishing.useStaging.set($staging)
            nexusPublishing.transitionCheckOptions.maxRetries.set(0)
            nexusPublishing.transitionCheckOptions.delayBetween.set(java.time.Duration.ZERO)
            nexusPublishing.repositories.create('probe') {
                publicationType.set(io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY)
                nexusUrl.set(uri('${model.server.endpoint}service/local'))
                snapshotRepositoryUrl.set(uri('${model.server.endpoint}direct/'))
                stagingProfileId.set('profile');allowInsecureProtocol.set(true)
                ${if (customLayout) "ivyPatternLayout { artifact('[organisation]/[module]/[revision]/[type]/[artifact]-[revision](-[classifier])(.[ext])');ivy('[organisation]/[module]/[revision]/[type]/[artifact]-[revision](.[ext])');m2compatible=true }" else ""}
            }
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle").writeText(if (rootPublication) publicationScript("root") else "group='probe';version='1.0'\n")
        if (rootPublication) javaSource(project)
        listOf("child", "other").forEach { name ->
            val child = File(project, name).apply { mkdirs() }
            File(child, "build.gradle").writeText(publicationScript(name))
            javaSource(child)
        }
        File(project, "empty").mkdirs()
        File(project, "empty/build.gradle").writeText("")
    }
    private fun publicationScript(name: String) =
        """
        plugins { id 'java-library';id 'maven-publish';id 'ivy-publish' }
        group='probe';version='1.0'
        publishing.publications {
            main(MavenPublication) { from components.java;artifactId='$name' }
            ivy(IvyPublication) { from components.java;module='$name' }
        }
        """.trimIndent() + "\n"
    private fun javaSource(project: File) {
        File(project, "src/main/java/probe/Example.java").apply {
            parentFile.mkdirs()
            writeText("package probe; public class Example {}\n")
        }
    }
    private class NexusModel(project: File) : java.io.Closeable {
        val server = MemoryRepository(File(project, "http-evidence"))
        val created = AtomicInteger()

        @Volatile var failUploads = false
        private val states = ConcurrentHashMap<String, String>()
        fun seed(id: String, state: String = "open") {
            states[id] = state
        }
        init {
            server.response = { method, path ->
                when {
                    method == "PUT" && failUploads -> MemoryRepository.Response(403)

                    method == "PUT" && path.startsWith("/service/local/staging/deployByRepositoryId/") && states[path.substringAfter("/deployByRepositoryId/").substringBefore('/')] != "open" -> MemoryRepository.Response(400)

                    method == "GET" && path.endsWith("/profile_repositories/profile") -> MemoryRepository.Response(200, data = """{"data":[{"repositoryId":"found","description":"ivy recovery"}]}""")

                    method == "POST" && path.endsWith("/start") -> {
                        val id = "created-${created.incrementAndGet()}"
                        seed(id)
                        MemoryRepository.Response(201, data = "{\"data\":{\"stagedRepositoryId\":\"$id\"}}")
                    }

                    method == "POST" && path.startsWith("/service/local/staging/bulk/") -> {
                        val body = Gson().fromJson(String(server.requests.last().third, Charsets.UTF_8), Map::class.java)
                        val id = (((body["data"] as Map<*, *>)["stagedRepositoryIds"] as List<*>).single()).toString()
                        if (!states.containsKey(id)) {
                            MemoryRepository.Response(404)
                        } else {
                            states[id] = if (path.endsWith("/close")) "closed" else "released"
                            MemoryRepository.Response(201)
                        }
                    }

                    method == "GET" && path.startsWith("/service/local/staging/repository/") -> {
                        val id = path.substringAfterLast('/')
                        if (states[id] == "released") {
                            MemoryRepository.Response(404, data = "{\"repositoryId\":\"$id\"}")
                        } else {
                            MemoryRepository.Response(200, data = "{\"repositoryId\":\"$id\",\"type\":\"${states[id]}\",\"transitioning\":false}")
                        }
                    }

                    else -> null
                }
            }
        }
        override fun close() =
            server.close()
    }
}
