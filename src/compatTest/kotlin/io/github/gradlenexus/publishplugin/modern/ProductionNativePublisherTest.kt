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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The real root task publishes detached, signed producer payloads through Gradle internals. */
internal class ProductionNativePublisherTest {
    private val key by lazy { SoftwareSigningKey() }
    private val projects = mutableListOf<File>()

    @AfterEach
    fun removeOwnedSigningCaches() {
        projects.forEach { project ->
            val cache = File(project, ".gradle/configuration-cache")
            check(!cache.exists() || cache.deleteRecursively()) { "Could not remove disposable signing cache" }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `root native publisher discovers late signed publications and advances existing snapshots on cache reuse`(runtime: ModernRuntime) {
        val project = File(ModernTestPaths.workspaceRoot, "production-native-publisher/${UUID.randomUUID()}").apply { mkdirs() }
        projects.add(project)
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            remote.expect("publisher", "owned-password")
            remote.seed("1.0-SNAPSHOT")
            File(project, "settings.gradle").writeText(
                """
                buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
                apply plugin: 'io.github.gradle-nexus.publish-plugin'
                rootProject.name = 'root'
                include 'child', 'empty'
                nexusPublishing.useStaging.set(false)
                nexusPublishing.repositories.create('probe') {
                    snapshotRepositoryUrl.set(uri('${remote.endpoint}'))
                    allowInsecureProtocol.set(true)
                    username.set('publisher')
                    password.set('owned-password')
                }
                """.trimIndent() + "\n"
            )
            File(project, "build.gradle").writeText("")
            File(project, "empty").mkdirs()
            val child = File(project, "child").apply { mkdirs() }
            File(child, "build.gradle").writeText(
                """
                plugins { id 'java-library'; id 'maven-publish'; id 'signing' }
                group = 'probe'; version = '1.0-SNAPSHOT'
                tasks.withType(Jar).configureEach { preserveFileTimestamps = false; reproducibleFileOrder = true }
                def custom = tasks.register('customArtifact') {
                    def target = layout.buildDirectory.file('custom.txt')
                    outputs.file(target)
                    doLast { target.get().asFile.text = 'custom-classifier-bytes' }
                }
                publishing.publications {
                    main(MavenPublication) {
                        from components.java; artifactId = 'native'
                        artifact(custom) { classifier = 'docs'; extension = 'txt' }
                    }
                }
                afterEvaluate {
                    publishing.publications.create('lateMetadata', MavenPublication) { artifactId = 'metadata-only' }
                    signing {
                        useInMemoryPgpKeys(providers.environmentVariable('BundleFixture_PGP_KEY').get(), providers.environmentVariable('BundleFixture_PGP_PASSWORD').get())
                        sign publishing.publications
                    }
                }
                """.trimIndent() + "\n"
            )
            val source = File(child, "src/main/java/probe/Example.java").apply { parentFile.mkdirs() }
            source.writeText("package probe; public class Example { public int value() { return 1; } }\n")
            val runner = ModernGradleBuild(project, false, runtime, mapOf("BundleFixture_PGP_KEY" to key.armored, "BundleFixture_PGP_PASSWORD" to key.password))
            var expectedBuild = 8
            var before = 0
            runner.storeAndReuse(":child:publishToProbe") { result ->
                assertThat(result.output).doesNotContain("owned-password", key.password, key.armored)
                assertThat(remote.authenticated.drop(before)).isNotEmpty().allMatch { it }
                val puts = remote.requests.drop(before).filter { it.first == "PUT" }
                assertThat(puts.map { it.second }).doesNotHaveDuplicates()
                assertThat(puts).isNotEmpty()
                val metadata = remote.files.getValue("/probe/native/1.0-SNAPSHOT/maven-metadata.xml").toString(Charsets.UTF_8)
                assertThat(metadata).contains("<buildNumber>$expectedBuild</buildNumber>")
                val timestamp = Regex("<timestamp>([^<]+)</timestamp>").find(metadata)!!.groupValues[1]
                val deployedVersion = "1.0-$timestamp-$expectedBuild"
                val manifest = Gson().fromJson(File(child, "build/nexus-publishing/bundles/maven/manifest.json").readText(), Map::class.java)
                val publications = manifest["publications"] as List<Map<String, Any>>
                assertThat(publications.map { it["name"] }).containsExactlyInAnyOrder("main", "lateMetadata")
                publications.forEach { publication ->
                    val entries = publication["entries"] as List<Map<String, Any>>
                    entries.filter { it["role"] != "checksum" }.forEach { entry ->
                        val logical = entry.getValue("destination").toString()
                        val actual = if (publication["module"] == "native") {
                            logical.replace("/native-1.0-SNAPSHOT", "/native-$deployedVersion")
                        } else {
                            val otherMetadata = remote.files.getValue("/probe/metadata-only/1.0-SNAPSHOT/maven-metadata.xml").toString(Charsets.UTF_8)
                            val otherTimestamp = Regex("<timestamp>([^<]+)</timestamp>").find(otherMetadata)!!.groupValues[1]
                            val otherNumber = Regex("<buildNumber>([^<]+)</buildNumber>").find(otherMetadata)!!.groupValues[1]
                            logical.replace("/metadata-only-1.0-SNAPSHOT", "/metadata-only-1.0-$otherTimestamp-$otherNumber")
                        }
                        val bytes = File(child, "build/nexus-publishing/bundles/maven/" + entry["payload"]).readBytes()
                        assertThat(remote.files["/$actual"]).isEqualTo(bytes)
                        if (entry["role"] == "signature") {
                            val target = entries.single { it["destination"] == entry["relatedTo"] }
                            assertThat(key.verifies(bytes, File(child, "build/nexus-publishing/bundles/maven/" + target["payload"]).readBytes())).isTrue()
                        }
                    }
                }
                resolveMaven(runtime, remote, child, expectedBuild)
                before = remote.requests.size
                expectedBuild++
            }
            assertThat(remote.files["/probe/native/1.0-SNAPSHOT/native-1.0-20200101.000000-7.jar"]).isEqualTo(byteArrayOf(1, 2, 3))
            val previous = remote.files.filterKeys { it.endsWith("-9.jar") }.mapValues { it.value.copyOf() }
            source.writeText("package probe; public class Example { public int value() { return 2; } }\n")
            val changed = runner.run(":child:publishToProbe")
            assertThat(changed.output).contains("Configuration cache entry reused.")
            val metadata = remote.files.getValue("/probe/native/1.0-SNAPSHOT/maven-metadata.xml").toString(Charsets.UTF_8)
            assertThat(metadata).contains("<buildNumber>10</buildNumber>")
            previous.forEach { (path, bytes) -> assertThat(remote.files[path]).isEqualTo(bytes) }
            assertThat(previous).hasSize(1)
            assertThat(remote.files.entries.single { it.key.endsWith("-10.jar") }.value).isNotEqualTo(previous.values.single())
            resolveMaven(runtime, remote, child, 10)
            val bundle = File(child, "build/nexus-publishing/bundles/maven")
            val model = Gson().fromJson(File(bundle, "manifest.json").readText(), Map::class.java)

            @Suppress("UNCHECKED_CAST")
            val publications = model["publications"] as List<Map<String, Any>>

            @Suppress("UNCHECKED_CAST")
            val entries = publications.single { it["name"] == "main" }["entries"] as List<Map<String, Any>>
            entries.filter { it["role"] == "signature" }.forEach { entry ->
                val target = entries.single { it["destination"] == entry["relatedTo"] }
                val signature = File(bundle, entry["payload"].toString()).readBytes()
                assertThat(key.verifies(signature, File(bundle, target["payload"].toString()).readBytes())).isTrue()
                val suffix = entry["destination"].toString().substringAfter("native-1.0-SNAPSHOT")
                assertThat(remote.files.entries.single { it.key.contains("-10$suffix") }.value).isEqualTo(signature)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `root HTTP policies and ambiguous commit outcomes match independent native publishers for both formats`(runtime: ModernRuntime) {
        data class Case(val format: String, val mode: String, val primary: String, val remote: MemoryRepository, val attempts: ConcurrentHashMap<String, Int>, val control: File, val root: File)
        val remotes = mutableListOf<MemoryRepository>()
        try {
            val cases = listOf("Maven", "Ivy").flatMap { format ->
                listOf("redirect", "retry", "timeout-status", "unauthorized", "wrong", "authorized", "disconnect").map { mode ->
                    val parent = File(ModernTestPaths.workspaceRoot, "production-native-http/${UUID.randomUUID()}").apply { mkdirs() }
                    val remote = MemoryRepository(File(parent, "http-evidence")).also { remotes.add(it) }
                    remote.expect("policy-user", "policy-private-value")
                    val primary = if (format == "Maven") "http-1.0.pom" else "ivy-1.0.xml"
                    val attempts = ConcurrentHashMap<String, Int>()
                    remote.response = { method, path ->
                        if (method != "PUT" || !path.endsWith("/$primary") || path.startsWith("/redirected/")) {
                            null
                        } else {
                            val count = attempts.merge(path, 1) { old, added -> old + added }!!
                            when (mode) {
                                "redirect" -> MemoryRepository.Response(307, location = "${remote.endpoint}redirected" + path)
                                "retry" -> if (count == 1) MemoryRepository.Response(503) else null
                                "timeout-status" -> if (count == 1) MemoryRepository.Response(408) else null
                                "disconnect" -> if (count == 1) MemoryRepository.Response(201, commit = true, disconnect = true) else null
                                else -> null
                            }
                        }
                    }
                    val control = File(parent, "control").apply { mkdirs() }
                    val root = File(parent, "root").apply { mkdirs() }
                    val publication = """
                        plugins { id '${format.toLowerCase()}-publish' }
                        group = 'probe'; version = '1.0'
                        publishing.publications { main(${format}Publication) { ${if (format == "Maven") "artifactId" else "module"} = 'http' } }
                    """.trimIndent() + "\n"
                    val password = if (mode == "wrong") "wrong-private-value" else "policy-private-value"
                    listOf(control, root).forEach { File(it, "build.gradle").writeText(publication) }
                    File(control, "settings.gradle").writeText("rootProject.name = 'native'\n")
                    if (mode != "unauthorized") File(control, "gradle.properties").writeText("controlUsername=policy-user\ncontrolPassword=$password\n")
                    File(control, "build.gradle").appendText(
                        """
                        publishing.repositories {
                            ${format.toLowerCase()} {
                                name = 'control'; url = uri('${remote.endpoint}control/named/'); allowInsecureProtocol = true
                                ${if (format == "Ivy") "layout 'maven'" else ""}
                                ${if (mode == "unauthorized") "" else "credentials(PasswordCredentials); authentication { basic(BasicAuthentication) }"}
                            }
                        }
                        """.trimIndent() + "\n"
                    )
                    File(root, "settings.gradle").writeText(
                        """
                        buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
                        apply plugin: 'io.github.gradle-nexus.publish-plugin'
                        rootProject.name = 'native'
                        nexusPublishing.useStaging.set(false)
                        nexusPublishing.repositories.create('probe') {
                            snapshotRepositoryUrl.set(uri('${remote.endpoint}root/named/')); allowInsecureProtocol.set(true)
                            ${if (format == "Ivy") "publicationType.set(io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY)" else ""}
                            ${if (mode == "unauthorized") "" else "username.set('policy-user'); password.set('$password')"}
                        }
                        """.trimIndent() + "\n"
                    )
                    Case(format, mode, primary, remote, attempts, control, root)
                }
            }
            val builds = IndependentGradleBuilds(cases.flatMap { listOf(it.control, it.root) }, runtime)
            repeat(2) { index ->
                cases.forEach { it.attempts.clear() }
                val beforeNative = cases.associate { it.root to it.remote.requests.size }
                val natives = builds.run(cases.associate { it.control to listOf(":publishMainPublicationToControlRepository") })
                val nativeRequests = cases.associate { it.root to it.remote.requests.drop(beforeNative.getValue(it.root)).filter { request -> request.first == "PUT" } }
                val nativeWrites = nativeRequests.mapValues { (_, requests) -> requests.map { it.second.replace("/control/", "/destination/") } }
                cases.forEach { it.attempts.clear() }
                val beforeRoots = cases.associate { it.root to it.remote.requests.size }
                val actuals = builds.run(cases.associate { it.root to listOf(":publishToProbe") })
                cases.forEach { (format, mode, primary, remote, _, control, root) ->
                    val beforeRoot = beforeRoots.getValue(root)
                    val native = natives.getValue(control)
                    val actual = actuals.getValue(root)
                    val nativeSuccess = native.tasks.none { it.outcome == org.gradle.testkit.runner.TaskOutcome.FAILED }
                    val actualSuccess = actual.tasks.none { it.outcome == org.gradle.testkit.runner.TaskOutcome.FAILED }
                    assertThat(native.task(":publishMainPublicationToControlRepository")).isNotNull()
                    assertThat(actual.tasks.any { it.path.endsWith("_upload") }).isTrue()
                    val rootRequests = remote.requests.drop(beforeRoot).filter { it.first == "PUT" }
                    val rootWrites = rootRequests.map { it.second.replace("/root/", "/destination/") }
                    println("NATIVE_HTTP_POLICY format=$format mode=$mode runtime=${runtime.version} invocation=$index nativeSuccess=$nativeSuccess rootSuccess=$actualSuccess nativeAttempts=${nativeWrites.getValue(root).groupingBy { it }.eachCount()} rootAttempts=${rootWrites.groupingBy { it }.eachCount()}")
                    assertThat(actualSuccess).isEqualTo(nativeSuccess)
                    assertThat(rootWrites.groupingBy { it }.eachCount()).isEqualTo(nativeWrites.getValue(root).groupingBy { it }.eachCount())
                    assertThat(rootWrites).isNotEmpty()
                    listOf(native, actual).forEach { result ->
                        assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                            .doesNotContain("policy-private-value", "wrong-private-value")
                    }
                    if (!actualSuccess) {
                        if (mode in listOf("unauthorized", "wrong")) assertThat(IndependentGradleBuilds.failureOutput(actual)).contains("401")
                        assertThat(File(root, "build/nexus-publishing/workflows").walkTopDown().none { it.name == "upload.json" }).isTrue()
                    }
                    if (mode == "authorized") {
                        assertThat(actualSuccess).isTrue()
                        assertThat(remote.authenticated.drop(beforeRoot)).containsOnly(true)
                        assertThat(remote.files.keys.all { it.startsWith("/control/named/") || it.startsWith("/root/named/") }).isTrue()
                        assertThat(remote.files.values.none { it.toString(Charsets.UTF_8).contains("policy-private-value") }).isTrue()
                    }
                    if (mode == "disconnect") {
                        listOf(nativeRequests.getValue(root), rootRequests).forEach { requests ->
                            val first = requests.first { it.second.endsWith("/$primary") }
                            assertThat(remote.files[first.second]).describedAs("ambiguous first PUT really committed the unchanged body").isEqualTo(first.third)
                        }
                    }
                    assertThat(remote.requests.none { it.second.startsWith("/service/") }).isTrue()
                }
            }
        } finally {
            remotes.forEach { it.close() }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `incomplete root credentials reject both formats before any request`(runtime: ModernRuntime) {
        listOf("Maven", "Ivy").forEach { format ->
            val root = File(ModernTestPaths.workspaceRoot, "production-native-incomplete/${UUID.randomUUID()}").apply { mkdirs() }
            MemoryRepository(File(root, "http-evidence")).use { remote ->
                File(root, "settings.gradle").writeText(
                    """
                    buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
                    apply plugin: 'io.github.gradle-nexus.publish-plugin'
                    rootProject.name = 'native'
                    nexusPublishing.useStaging.set(false)
                    nexusPublishing.repositories.create('probe') {
                        snapshotRepositoryUrl.set(uri('${remote.endpoint}')); allowInsecureProtocol.set(true); username.set('policy-user')
                        ${if (format == "Ivy") "publicationType.set(io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY)" else ""}
                    }
                    """.trimIndent() + "\n"
                )
                File(root, "build.gradle").writeText("plugins { id '${format.toLowerCase()}-publish' }; group='probe';version='1.0';publishing.publications { main(${format}Publication) {} }\n")
                val build = ModernGradleBuild(root, false, runtime)
                repeat(2) { index ->
                    val result = build.failWithDiagnostic("NEXUS_INCOMPLETE_CREDENTIALS", ":publishToProbe")
                    assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                    assertThat(remote.requests).isEmpty()
                }
            }
        }
    }

    private fun resolveMaven(runtime: ModernRuntime, remote: MemoryRepository, producer: File, generation: Int) {
        listOf("module", "pom").forEach { mode ->
            // A new project and TestKit cache per generation and mode excludes cached snapshot answers.
            val consumer = File(ModernTestPaths.workspaceRoot, "production-native-consumer/${UUID.randomUUID()}/consumer").apply { mkdirs() }
            File(consumer, "settings.gradle").writeText("rootProject.name = 'fresh-consumer'\n")
            File(consumer, "build.gradle").writeText(
                """
                plugins { id 'java-library' }
                repositories {
                    maven {
                        url = uri('${remote.endpoint}'); allowInsecureProtocol = true
                        credentials { username = 'publisher'; password = 'owned-password' }
                        metadataSources { ${if (mode == "module") "gradleMetadata()" else "mavenPom(); ignoreGradleMetadataRedirection()"} }
                    }
                }
                dependencies { implementation 'probe:native:1.0-SNAPSHOT' }
                tasks.register('resolveArtifact', Sync) { from configurations.compileClasspath; into layout.buildDirectory.dir('resolved') }
                """.trimIndent() + "\n"
            )
            val before = remote.requests.size
            ModernGradleBuild(consumer, false, runtime, isolatedDependencyCache = true).storeAndReuse("resolveArtifact")
            val resolved = File(consumer, "build/resolved").listFiles()!!.single()
            assertThat(resolved.readBytes()).isEqualTo(File(producer, "build/libs/child-1.0-SNAPSHOT.jar").readBytes())
            val requests = remote.requests.drop(before).filter { it.first == "GET" }
            assertThat(requests.filter { it.second.endsWith(".jar") }).hasSize(1)
            assertThat(requests.single { it.second.endsWith(".jar") }.second).endsWith("-$generation.jar")
            val expected = if (mode == "module") ".module" else ".pom"
            assertThat(requests.any { it.second.endsWith(expected) && remote.files.containsKey(it.second) }).isTrue()
            if (mode == "pom") assertThat(requests.none { it.second.endsWith(".module") }).isTrue()
        }
    }
}
