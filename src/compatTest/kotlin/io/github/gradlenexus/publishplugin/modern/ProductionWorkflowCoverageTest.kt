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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Remaining historical workflow body assertions executed against the actual packaged candidate. */
internal class ProductionWorkflowCoverageTest {
    private val key by lazy { SoftwareSigningKey() }
    private val signedProjects = mutableListOf<File>()

    @AfterEach fun removeOwnedSigningCaches() {
        signedProjects.forEach { project ->
            val cache = File(project, ".gradle/configuration-cache")
            check(!cache.exists() || cache.deleteRecursively()) { "Owned signing cache could not be removed" }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `same build selection switch removes unselected staged units on restored cache entries`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model)
            val build = ModernGradleBuild(project, false, runtime)
            val invocations = mutableSetOf<String>()
            var before = 0
            listOf(true, true, false, false, true, true).forEachIndexed { index, both ->
                val tasks = if (both) arrayOf(":child:publishToProbe", ":other:publishToProbe") else arrayOf(":child:publishToProbe")
                val result = build.run(*tasks)
                assertThat(result.output).contains(if (index == 0 || index == 2) "Configuration cache entry stored." else "Configuration cache entry reused.")
                val session = record(project, "session.json")
                assertThat(invocations.add(session["invocation"].toString())).isTrue()
                assertThat(session["units"]).isEqualTo(if (both) listOf("p1", "p3") else listOf("p1"))
                assertThat(session["repositoryId"]).isEqualTo("created-${index + 1}")
                val puts = model.server.requests.drop(before).filter { it.first == "PUT" }
                assertThat(puts).isNotEmpty().allMatch { it.second.startsWith("/service/local/staging/deployByRepositoryId/created-${index + 1}/") }
                val selected = if (both) listOf("child", "other") else listOf("child")
                selected.forEach { name -> assertThat(model.server.files["/service/local/staging/deployByRepositoryId/created-${index + 1}/probe/$name/1.0/$name-1.0.jar"]).isEqualTo(File(project, "$name/build/libs/$name-1.0.jar").readBytes()) }
                if (!both) {
                    assertThat(File(workspace(project), "staged/p3")).doesNotExist()
                    assertThat(result.tasks.none { it.path.startsWith(":other:") || it.path.endsWith("_intent3") || it.path.endsWith("_preflight3") }).isTrue()
                    assertThat(result.output).doesNotContain("NEXUS-RESOLVE :other")
                    assertThat(puts.none { it.second.contains("/other/") }).isTrue()
                }
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `removing project and repository contexts cannot reuse warm stage or session data`(runtime: ModernRuntime) {
        listOf("project", "repository").forEach { mode ->
            val project = directory()
            NexusModel(project).use { primary ->
                NexusModel(File(project, "secondary-model").apply { mkdirs() }).use { secondary ->
                    configure(project, primary)
                    val extra = repository("secondary", secondary)
                    if (mode == "repository") File(project, "settings.gradle").appendText(extra)
                    val build = ModernGradleBuild(project, false, runtime)
                    val warm = if (mode == "project") arrayOf(":publishAllProjectsToProbe", ":closeAndReleaseStagingRepositories") else arrayOf(":child:publishToProbe", ":other:publishToSecondary", ":closeAndReleaseStagingRepositories")
                    build.storeAndReuse(*warm)
                    val secondaryBefore = secondary.server.requests.size
                    val oldSecondary = if (mode == "repository") File(workspace(project, "secondary"), "session.json").readBytes() else null
                    val settings = File(project, "settings.gradle")
                    settings.writeText(if (mode == "project") settings.readText().replace("include 'child','other','empty'", "include 'child','empty'") else settings.readText().replace(extra, ""))
                    var before = primary.server.requests.size
                    repeat(2) { index ->
                        val result = build.run(":publishAllProjectsToProbe", ":closeAndReleaseStagingRepositories")
                        cache(result, index)
                        val paths = result.tasks.map { it.path }
                        if (mode == "project") {
                            assertThat(paths.none { it.startsWith(":other:") || it.endsWith("_intent3") || it.endsWith("_preflight3") }).isTrue()
                            assertThat(result.output).doesNotContain("NEXUS-RESOLVE :other")
                            assertThat(File(workspace(project), "staged/p3")).doesNotExist()
                            assertThat(File(project, "other/build/nexus-publishing/bundles/maven/manifest.json")).isFile()
                            assertThat(record(project, "session.json")["units"]).isEqualTo(listOf("p0", "p1", "p2"))
                        } else {
                            assertThat(paths.none { it.contains("Secondary") || it.startsWith(":" + workspace(project, "secondary").name + "_") }).isTrue()
                            assertThat(File(workspace(project, "secondary"), "session.json").readBytes()).isEqualTo(oldSecondary)
                        }
                        val writes = primary.server.requests.drop(before).filter { it.first == "PUT" }
                        assertThat(writes).isNotEmpty()
                        if (mode == "project") assertThat(writes.none { it.second.contains("/other/") }).isTrue()
                        assertThat(secondary.server.requests.size).isEqualTo(secondaryBefore)
                        assertThat(result.task(":releaseProbeStagingRepository")!!.outcome).isEqualTo(TaskOutcome.SUCCESS)
                        before = primary.server.requests.size
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `staged native preparation failures reject before creation after warm success`(runtime: ModernRuntime) {
        listOf("prepare-disabled", "export-disabled", "missing-input", "before-action").forEach { mode ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model)
                val build = ModernGradleBuild(project, false, runtime)
                build.storeAndReuse(":child:publishToProbe")
                val customization = when (mode) {
                    "prepare-disabled" -> "tasks.named('prepareNexusMavenBundle') { enabled=false }"
                    "export-disabled" -> "tasks.named('exportNexusMavenMain') { enabled=false }"
                    "missing-input" -> "tasks.named('exportNexusMavenMain') { inputs.file(layout.projectDirectory.file('missing-native-input')) }"
                    else -> "tasks.named('exportNexusMavenMain') { doFirst { throw new GradleException('OWNED_PRODUCER_NATIVE_BEFORE_ACTION') } }"
                }
                File(project, "child/build.gradle").appendText("\n$customization\n")
                val before = model.server.requests.size
                repeat(2) { index ->
                    val result = build.failContinuingWithDiagnostic("NEXUS_WORKFLOW_NOT_SUCCESSFUL", ":child:publishToProbe", ":closeAndReleaseStagingRepositories")
                    cache(result, index)
                    assertThat(model.server.requests.size).isEqualTo(before)
                    listOf("session.json", "upload.json", "close.json", "release.json").forEach { assertThat(File(workspace(project), it)).doesNotExist() }
                    val task = if (mode == "prepare-disabled") ":child:prepareNexusMavenBundle" else ":child:exportNexusMavenMain"
                    assertThat(result.task(task)!!.outcome).isEqualTo(if (mode.endsWith("disabled")) TaskOutcome.SKIPPED else TaskOutcome.FAILED)
                    if (mode == "missing-input") assertThat(result.output).contains("missing-native-input", "doesn't exist")
                    if (mode == "before-action") assertThat(result.output).contains("OWNED_PRODUCER_NATIVE_BEFORE_ACTION").doesNotContain("NEXUS-NATIVE-EXPORTED :child:exportNexusMavenMain")
                }
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `changed staged manifest and payload after acquisition preserve ID but reject upload`(runtime: ModernRuntime) {
        listOf("manifest", "payload").forEach { mode ->
            val project = directory()
            NexusModel(project).use { model ->
                configure(project, model)
                val build = ModernGradleBuild(project, false, runtime)
                build.storeAndReuse(":child:publishToProbe")
                val mutation = if (mode == "manifest") {
                    "new File(task.staged.get().asFile,'p1/manifest.json').append(' ')"
                } else {
                    """
                    def directory=new File(task.staged.get().asFile,'p1')
                    def manifest=new groovy.json.JsonSlurper().parse(new File(directory,'manifest.json'))
                    def artifact=manifest.publications.collectMany { it.entries }.find { it.role=='artifact' }
                    def file=new File(directory,artifact.payload);def bytes=file.bytes;bytes[0]=(byte)(bytes[0]^1);file.bytes=bytes
                    """.trimIndent()
                }
                File(project, "build.gradle").appendText("\ntasks.withType(io.github.gradlenexus.publishplugin.internal.modern.workflow.WorkflowUpload).configureEach { doFirst { task -> $mutation } }\n")
                var before = model.server.requests.size
                var recoverId = ""
                repeat(2) { index ->
                    val expected = if (mode == "manifest") "NEXUS_VALIDATED_PLAN_CHANGED" else "NEXUS_INVALID_BUNDLE: digest mismatch"
                    val result = build.failContinuingWithDiagnostic(expected, ":child:publishToProbe", ":closeAndReleaseStagingRepositories")
                    cache(result, index)
                    recoverId = "created-${model.created.get()}"
                    assertThat(result.output).contains("NEXUS-WORKFLOW-REPOSITORY repository=probe id=$recoverId")
                    assertThat(record(project, "session.json")["repositoryId"]).isEqualTo(recoverId)
                    assertThat(result.tasks.single { it.path.endsWith("_session") }.outcome).isEqualTo(TaskOutcome.SUCCESS)
                    assertThat(result.tasks.single { it.path.endsWith("_upload") }.outcome).isEqualTo(TaskOutcome.FAILED)
                    assertThat(model.server.requests.drop(before).map { it.first to it.second }).containsExactly("POST" to "/service/local/staging/profiles/profile/start")
                    listOf("upload.json", "close.json", "release.json").forEach { assertThat(File(workspace(project), it)).doesNotExist() }
                    before = model.server.requests.size
                }
                val recovered = build.runRecovery(StagingRecoveryTask(":closeProbeStagingRepository", recoverId), StagingRecoveryTask(":releaseProbeStagingRepository", recoverId))
                assertThat(recovered.tasks.none { it.path.startsWith(":child:") || listOf("_validate", "_session", "_upload").any { suffix -> it.path.endsWith(suffix) } }).isTrue()
                assertThat(model.server.requests.drop(before).filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                assertThat(model.server.requests.drop(before).none { it.first == "PUT" }).isTrue()
                assertThat(record(project, "release.json")["repositoryId"]).isEqualTo(recoverId)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `local preparation A cannot enroll A in publication B lifecycle`(runtime: ModernRuntime) {
        val project = directory()
        NexusModel(project).use { model ->
            configure(project, model)
            var before = 0
            ModernGradleBuild(project, false, runtime).storeAndReuse(":child:prepareNexusMavenBundle", ":other:publishToProbe", ":closeAndReleaseStagingRepositories") { result ->
                assertThat(File(project, "child/build/nexus-publishing/bundles/maven/manifest.json")).isFile()
                assertThat(result.tasks.none { it.path.endsWith("_intent1") || it.path.endsWith("_preflight1") }).isTrue()
                assertThat(result.output).doesNotContain("NEXUS-RESOLVE :child maven")
                val session = record(project, "session.json")
                val released = record(project, "release.json")
                assertThat(session["units"]).isEqualTo(listOf("p3"))
                assertThat(released["invocation"]).isEqualTo(session["invocation"])
                assertThat(released["planBinding"]).isEqualTo(session["planBinding"])
                val delta = model.server.requests.drop(before)
                assertThat(delta.filter { it.first == "PUT" }).isNotEmpty().allMatch { it.second.contains("/probe/other/") }
                assertThat(delta.filter { it.first == "POST" }.map { it.second }).containsExactly("/service/local/staging/profiles/profile/start", "/service/local/staging/bulk/close", "/service/local/staging/bulk/promote")
                before = model.server.requests.size
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `signed root Maven workflow preserves current bytes and fresh module consumer`(runtime: ModernRuntime) {
        val producer = directory()
        val control = directory()
        signedProjects.addAll(listOf(producer, control))
        NexusModel(producer).use { model ->
            configure(producer, model)
            File(producer, "settings.gradle").appendText("\nnexusPublishing.useStaging.set(false)\n")
            File(control, "settings.gradle").writeText("rootProject.name='coverage';include 'child'\n")
            listOf("root", "child").forEach { name ->
                val actual = if (name == "root") producer else File(producer, name)
                val native = if (name == "root") control else File(control, name).apply { mkdirs() }
                listOf(actual, native).forEach { project ->
                    File(project, "build.gradle").writeText(signedScript(name))
                    source(project)
                }
                File(native, "build.gradle").appendText("\npublishing.repositories { maven { name='control';url=uri('${File(control,"native-control").invariantSeparatorsPath}') } }\n")
            }
            val environment = mapOf("BundleFixture_PGP_KEY" to key.armored, "BundleFixture_PGP_PASSWORD" to key.password)
            ModernGradleBuild(control, false, runtime, environment).storeAndReuse(":publishMainPublicationToControlRepository", ":child:publishMainPublicationToControlRepository")
            val expected = File(control, "native-control").walkTopDown().filter { it.isFile }.associate { it.relativeTo(File(control, "native-control")).invariantSeparatorsPath to it.readBytes() }
            assertThat(expected).isNotEmpty()
            val expectedPaths = expected.keys
            var before = 0
            ModernGradleBuild(producer, false, runtime, environment).storeAndReuse(":publishToProbe", ":child:publishToProbe") { result ->
                assertThat(result.tasks.none { it.path.contains("Ivy") || it.path.contains("Opposite", ignoreCase = true) || it.path.startsWith(":other:") || it.path.startsWith(":empty:") }).isTrue()
                assertThat(Regex("NEXUS-RESOLVE .* ivy").containsMatchIn(result.output)).isFalse()
                assertThat(result.output.contains(key.password) || result.output.contains("BEGIN PGP PRIVATE KEY BLOCK")).describedAs("software signing material absent from logs").isFalse()
                val writes = model.server.requests.drop(before).filter { it.first == "PUT" }.map { it.second }
                assertThat(writes).containsExactlyInAnyOrderElementsOf(expectedPaths.map { "/direct/$it" }).doesNotHaveDuplicates()
                listOf("root", "child").forEach { name ->
                    val project = if (name == "root") producer else File(producer, name)
                    val bundle = File(project, "build/nexus-publishing/bundles/maven")
                    val entries = entries(bundle)
                    val primary = entries.filter { it["role"] in setOf("artifact", "pom", "gradle-module") }
                    val signatures = entries.filter { it["role"] == "signature" }
                    assertThat(primary).isNotEmpty()
                    assertThat(signatures.map { it["relatedTo"] }).containsExactlyInAnyOrderElementsOf(primary.map { it["destination"] })
                    entries.forEach { entry ->
                        val path = "/direct/" + entry["destination"]
                        val payload = File(bundle, entry["payload"].toString()).readBytes()
                        assertThat(model.server.files[path]).describedAs("current prepared $path").isEqualTo(payload)
                        if (entry["role"] == "signature") {
                            val target = entries.single { it["destination"] == entry["relatedTo"] }
                            assertThat(key.verifies(payload, File(bundle, target["payload"].toString()).readBytes())).isTrue()
                        }
                    }
                }
                expected.filterKeys { !it.contains("maven-metadata.xml") && !it.endsWith(".asc") && !it.matches(Regex(".*\\.asc\\.(md5|sha1|sha256|sha512)")) }.forEach { (path, bytes) ->
                    assertThat(model.server.files["/direct/$path"]).describedAs("independent native unsigned $path").isEqualTo(bytes)
                }
                writes.filterNot { it.matches(Regex(".*\\.(md5|sha1|sha256|sha512)")) }.forEach { path ->
                    val payload = model.server.files[path]!!
                    listOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512").forEach { (suffix, algorithm) ->
                        model.server.files["$path.$suffix"]?.let { checksum ->
                            assertThat(checksum.toString(Charsets.UTF_8).trim()).isEqualTo(digest(payload, algorithm))
                        }
                    }
                }
                before = model.server.requests.size
                // A new TestKit parent on each root upload proves actual consumption, not shared-cache fallback.
                listOf("root", "child").forEach { name -> consume(model.server, runtime, producer, name) }
                before = model.server.requests.size
            }
        }
    }

    private fun signedScript(name: String) =
        """
        plugins { id 'java-library';id 'maven-publish';id 'ivy-publish';id 'signing' }
        group='probe';version='1.0';base.archivesName='$name'
        java { withSourcesJar();withJavadocJar() }
        tasks.withType(Jar).configureEach { preserveFileTimestamps=false;reproducibleFileOrder=true }
        tasks.withType(Javadoc).configureEach { options.noTimestamp=true }
        configurations.apiElements.attributes.attribute(org.gradle.api.attributes.Attribute.of('probe.custom',String),'module-only')
        publishing.publications {
            main(MavenPublication) { from components.java;artifactId='$name';pom { description='signed root workflow' } }
            opposite(IvyPublication) { from components.java;module='$name-ivy' }
        }
        signing { useInMemoryPgpKeys(providers.environmentVariable('BundleFixture_PGP_KEY').get(),providers.environmentVariable('BundleFixture_PGP_PASSWORD').get());sign publishing.publications }
        """.trimIndent() + "\n"

    private fun consume(remote: MemoryRepository, runtime: ModernRuntime, producer: File, name: String) {
        val consumer = File(ModernTestPaths.workspaceRoot, "production-workflow-coverage-consumer/${UUID.randomUUID()}/consumer").apply { mkdirs() }
        File(consumer, "settings.gradle").writeText("rootProject.name='fresh-consumer'\n")
        File(consumer, "build.gradle").writeText(
            """
            plugins { id 'java-library' }
            repositories { maven { url=uri('${remote.endpoint}direct/');allowInsecureProtocol=true;metadataSources { gradleMetadata() } } }
            dependencies { implementation 'probe:$name:1.0' }
            tasks.register('resolveArtifact',Sync) { from configurations.compileClasspath;into layout.buildDirectory.dir('resolved') }
            """.trimIndent() + "\n"
        )
        val before = remote.requests.size
        ModernGradleBuild(consumer, false, runtime, isolatedDependencyCache = true).storeAndReuse("resolveArtifact")
        val expected = File(producer, if (name == "root") "build/libs/root-1.0.jar" else "child/build/libs/child-1.0.jar")
        val resolved = File(consumer, "build/resolved").listFiles()!!.single { it.name == "$name-1.0.jar" }
        assertThat(resolved.readBytes()).isEqualTo(expected.readBytes())
        java.util.jar.JarFile(resolved).use { assertThat(it.getJarEntry("probe/Example.class")).isNotNull() }
        assertThat(remote.requests.drop(before).any { it.first == "GET" && it.second == "/direct/probe/$name/1.0/$name-1.0.module" && remote.files.containsKey(it.second) }).isTrue()
    }

    @Suppress("UNCHECKED_CAST")
    private fun entries(bundle: File): List<Map<String, Any?>> =
        URLClassLoader(arrayOf(ModernTestPaths.candidateJar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val model = loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec").getMethod("readModel", Path::class.java).invoke(null, bundle.toPath()) as Map<String, Any?>
            (model["publications"] as List<Map<String, Any?>>).flatMap { it["entries"] as List<Map<String, Any?>> }
        }
    private fun digest(bytes: ByteArray, algorithm: String) =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-workflow-coverage/${UUID.randomUUID()}/consumer").apply { mkdirs() }
    private fun workspace(project: File, repository: String = "probe"): File =
        File(project, "build/nexus-publishing/workflows/nexus_" + digest((project.toPath().toAbsolutePath().normalize().toString() + "\u0000" + repository).toByteArray(Charsets.UTF_8), "SHA-256"))
    private fun record(project: File, name: String, repository: String = "probe"): Map<*, *> =
        Gson().fromJson(File(workspace(project, repository), name).readText(), Map::class.java)
    private fun cache(result: BuildResult, index: Int) {
        if (index == 0) {
            assertThat(result.output.contains("Configuration cache entry stored.") || Regex("Configuration cache entry updated for .*projects.*").containsMatchIn(result.output)).isTrue()
        } else {
            assertThat(result.output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
        }
    }
    private fun repository(name: String, model: NexusModel) =
        """
        nexusPublishing.repositories.create('$name') {
            nexusUrl.set(uri('${model.server.endpoint}service/local'));snapshotRepositoryUrl.set(uri('${model.server.endpoint}direct/'))
            stagingProfileId.set('profile');allowInsecureProtocol.set(true)
        }
        """.trimIndent() + "\n"
    private fun configure(project: File, model: NexusModel) {
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin:'io.github.gradle-nexus.publish-plugin'
            rootProject.name='coverage'
            include 'child','other','empty'
            nexusPublishing.transitionCheckOptions.maxRetries.set(0)
            nexusPublishing.transitionCheckOptions.delayBetween.set(java.time.Duration.ZERO)
            """.trimIndent() + "\n" + repository("probe", model)
        )
        File(project, "build.gradle").writeText("group='probe';version='1.0'\n")
        listOf("child", "other").forEach { name ->
            val child = File(project, name).apply { mkdirs() }
            File(child, "build.gradle").writeText("plugins { id 'java-library';id 'maven-publish' };group='probe';version='1.0';publishing.publications { main(MavenPublication) { from components.java;artifactId='$name' } }\n")
            source(child)
        }
        File(project, "empty").mkdirs()
        File(project, "empty/build.gradle").writeText("")
    }
    private fun source(project: File) {
        File(project, "src/main/java/probe/Example.java").apply {
            parentFile.mkdirs()
            writeText("package probe; /** Example. */ public class Example { public static int value() { return 1; } }\n")
        }
    }
    private class NexusModel(project: File) : java.io.Closeable {
        val server = MemoryRepository(File(project, "http-evidence"))
        val created = AtomicInteger()
        private val states = ConcurrentHashMap<String, String>()
        init {
            server.response = { method, path ->
                when {
                    method == "POST" && path.endsWith("/start") -> {
                        val id = "created-${created.incrementAndGet()}"
                        states[id] = "open"
                        MemoryRepository.Response(201, data = "{\"data\":{\"stagedRepositoryId\":\"$id\"}}")
                    }

                    method == "PUT" && path.startsWith("/service/local/staging/deployByRepositoryId/") && states[path.substringAfter("/deployByRepositoryId/").substringBefore('/')] != "open" -> MemoryRepository.Response(409)

                    method == "POST" && path.startsWith("/service/local/staging/bulk/") -> {
                        val body = Gson().fromJson(String(server.requests.last().third, Charsets.UTF_8), Map::class.java)
                        val id = (((body["data"] as Map<*, *>)["stagedRepositoryIds"] as List<*>).single()).toString()
                        when {
                            path.endsWith("/close") && states[id] == "open" -> {
                                states[id] = "closed"
                                MemoryRepository.Response(201)
                            }

                            path.endsWith("/promote") && states[id] == "closed" -> {
                                states[id] = "released"
                                MemoryRepository.Response(201)
                            }

                            else -> MemoryRepository.Response(409)
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
