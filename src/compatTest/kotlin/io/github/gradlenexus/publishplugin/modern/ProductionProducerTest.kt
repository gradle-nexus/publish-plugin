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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Actual packaged producer/variant dataflow and native bytes. */
internal class ProductionProducerTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `scoped bundles preserve independent native publication bytes on each cache invocation`(runtime: ModernRuntime) {
        listOf("Maven", "Ivy").forEach { format ->
            val project = consumer()
            val lower = format.toLowerCase()
            val control = directory("native-control")
            File(control, "settings.gradle").writeText("rootProject.name = 'native'\n")
            File(control, "build.gradle").writeText(
                publications() + """
                publishing.repositories {
                    maven { name = 'controlMaven'; url = layout.buildDirectory.dir('control/maven') }
                    ivy { name = 'controlIvy'; url = layout.buildDirectory.dir('control/ivy'); layout 'maven' }
                }
                """.trimIndent()
            )
            source(control)
            ModernGradleBuild(control, false, runtime).storeAndReuse("publish${format}PubPublicationToControl${format}Repository")
            val expected = tree(File(control, "build/control/$lower")).filterKeys { !localMetadata(it.substringAfterLast('/')) }
            val bundle = File(project, "child/build/nexus-publishing/bundles/$lower")
            ModernGradleBuild(project, false, runtime).storeAndReuse(":collectNexus${format}Bundle1") { result ->
                assertThat(result.output).contains("NEXUS-COLLECTED :child $lower empty=false")
                    .doesNotContain("NEXUS-RESOLVE :empty", "NEXUS-RESOLVE :excluded", "NEXUS-COLLECTED : $lower")
                val other = if (format == "Maven") "Ivy" else "Maven"
                assertThat(result.tasks.none { it.path.contains("${other}Bundle") || it.path.contains("${other.toLowerCase()}Pub") }).isTrue()
                if (result.output.contains("Configuration cache entry stored.")) {
                    assertThat(result.output).contains("NEXUS-RESOLVE :child $lower").doesNotContain("NEXUS-RESOLVE :child ${other.toLowerCase()}")
                }
                val model = model(bundle)
                assertThat(model["producerPath"]).isEqualTo(":child")
                val entries = entries(model)
                val actual = entries.associate { it["destination"].toString().substringAfterLast('/') to hash(File(bundle, it["payload"].toString())) }
                val expectedByName = expected.mapKeys { it.key.substringAfterLast('/') }
                assertThat(actual).isEqualTo(expectedByName)
                assertThat(entries.filter { it["role"] in listOf("pom", "ivy-descriptor", "gradle-module") }).hasSize(2)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `root local empty and excluded routes avoid unrelated projects and self cycles`(runtime: ModernRuntime) {
        val project = consumer()
        val runner = ModernGradleBuild(project, false, runtime)
        runner.storeAndReuse(":collectNexusMavenBundle0", ":collectNexusIvyBundle2", ":collectNexusMavenBundle3") { result ->
            assertThat(result.output).contains("NEXUS-COLLECTED : maven empty=true", "NEXUS-COLLECTED :empty ivy empty=true", "NEXUS-EXCLUDED :excluded maven")
            assertThat(result.tasks.none { it.path.startsWith(":child:") || it.path.startsWith(":excluded:") }).isTrue()
        }
        File(project, "build.gradle").appendText(
            """
            apply plugin: 'maven-publish'
            group = 'root.group'; version = '1.0'
            publishing.publications.create('rootMetadata', org.gradle.api.publish.maven.MavenPublication) { artifactId = 'root-metadata' }
            """.trimIndent()
        )
        runner.storeAndReuse(":collectNexusMavenBundle0") { result ->
            assertThat(result.output).contains("NEXUS-COLLECTED : maven empty=false")
            assertThat(result.tasks.none { it.path.startsWith(":child:") }).isTrue()
        }
        val rootModel = model(File(project, "build/nexus-publishing/bundles/maven"))
        val publication = publications(rootModel).single()
        assertThat(publication["kind"]).isEqualTo("metadata-only")
        assertThat(publication["artifacts"] as List<*>).isEmpty()
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `late additions and removals update local payloads and both variant task dependencies`(runtime: ModernRuntime) {
        val project = consumer()
        val script = File(project, "child/build.gradle")
        script.appendText(
            """
            tasks.register('localPrerequisite') { doLast { logger.lifecycle('LOCAL_PREREQUISITE') } }
            tasks.named('prepareNexusMavenBundle') { dependsOn 'localPrerequisite' }
            afterEvaluate {
                publishing.publications.create('lateMetadata', org.gradle.api.publish.maven.MavenPublication) {
                    groupId = 'late.group'; artifactId = 'late-metadata'; version = '1.0'
                }
            }
            """.trimIndent()
        )
        val runner = ModernGradleBuild(project, false, runtime)
        runner.storeAndReuse(":collectNexusMavenBundle1") { assertThat(it.output).contains("LOCAL_PREREQUISITE") }
        val bundle = File(project, "child/build/nexus-publishing/bundles/maven")
        assertThat(publications(model(bundle)).map { it["name"] }).containsExactlyInAnyOrder("mavenPub", "lateMetadata")
        script.appendText("\nafterEvaluate { publishing.publications.remove(publishing.publications.getByName('mavenPub')) }\n")
        runner.storeAndReuse(":collectNexusMavenBundle1") { result ->
            assertThat(result.output).contains("LOCAL_PREREQUISITE")
            assertThat(result.tasks.none { it.path == ":child:exportNexusMavenMavenPub" }).isTrue()
            assertThat(publications(model(bundle)).map { it["name"] }).containsExactly("lateMetadata")
            assertThat(File(bundle, "payload/${namespace("mavenPub")}").exists()).isFalse()
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `disabled exporter cannot reuse its old success witness`(runtime: ModernRuntime) {
        val project = consumer()
        val runner = ModernGradleBuild(project, false, runtime)
        runner.storeAndReuse(":collectNexusMavenBundle1")
        File(project, "child/build.gradle").appendText("\ntasks.named('exportNexusMavenMavenPub') { enabled = false }\n")
        repeat(2) { index ->
            val result = runner.failWithDiagnostic("NEXUS_EXPORT_DID_NOT_SUCCEED", ":collectNexusMavenBundle1")
            assertThat(result.output).doesNotContain("NEXUS-COLLECTED :child maven")
            assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
            assertThat(File(project, "child/build/nexus-publishing/bundles/maven/manifest.json").exists()).isFalse()
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `known skipped reset and exporter counterexample validates reusable old bytes without admission`(runtime: ModernRuntime) {
        val project = consumer()
        val runner = ModernGradleBuild(project, false, runtime)
        runner.storeAndReuse(":collectNexusMavenBundle1")
        val bundle = File(project, "child/build/nexus-publishing/bundles/maven")
        val artifact = entries(model(bundle)).single { it["role"] == "artifact" }
        val previous = hash(File(bundle, artifact["payload"].toString()))
        source(File(project, "child"), 2)
        File(project, "child/build.gradle").appendText(
            """
            tasks.named('resetNexusMavenBundle') { enabled = false }
            tasks.named('exportNexusMavenMavenPub') { enabled = false }
            """.trimIndent()
        )
        runner.storeAndReuse(":collectNexusMavenBundle1") { result ->
            assertThat(result.output).contains("NEXUS-COLLECTED :child maven empty=false")
            assertThat(result.task(":child:resetNexusMavenBundle")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
            assertThat(result.task(":child:exportNexusMavenMavenPub")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
            assertThat(hash(File(bundle, artifact["payload"].toString()))).isEqualTo(previous)
            assertThat(hash(File(project, "child/build/libs/child-1.0.jar"))).isNotEqualTo(previous)
        }
        // Deliberately retained producer obligation: local file validity is not fresh selected-workflow admission.
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `late native destination mutation fails before any loopback request`(runtime: ModernRuntime) {
        listOf(false, true).forEach { replace ->
            val project = consumer()
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                File(project, "child/build.gradle").appendText(
                    if (replace) {
                        """
                    def unexpected = repositories.maven { url = uri('${remote.endpoint}'); allowInsecureProtocol = true }
                    repositories.remove(unexpected)
                    tasks.named('exportNexusMavenMavenPub') { repository = unexpected }
                        """.trimIndent()
                    } else {
                        """
                    tasks.named('exportNexusMavenMavenPub') { repository.url = uri('${remote.endpoint}'); repository.allowInsecureProtocol = true }
                        """.trimIndent()
                    }
                )
                ModernGradleBuild(project, false, runtime).failWithDiagnostic("NEXUS_NATIVE_DESTINATION_REJECTED", ":collectNexusMavenBundle1")
                assertThat(remote.requests).isEmpty()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `known skipped reset and preparation counterexample accepts an old valid empty manifest`(runtime: ModernRuntime) {
        val project = consumer()
        val runner = ModernGradleBuild(project, false, runtime)
        runner.storeAndReuse(":collectNexusIvyBundle2")
        val manifest = File(project, "empty/build/nexus-publishing/bundles/ivy/manifest.json")
        val original = manifest.readBytes()
        File(project, "empty/build.gradle").appendText(
            """
            tasks.named('resetNexusIvyBundle') { enabled = false }
            tasks.named('prepareNexusIvyBundle') { enabled = false }
            """.trimIndent()
        )
        runner.storeAndReuse(":collectNexusIvyBundle2") { result ->
            assertThat(result.output).contains("NEXUS-COLLECTED :empty ivy empty=true")
            assertThat(result.task(":empty:resetNexusIvyBundle")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
            assertThat(result.task(":empty:prepareNexusIvyBundle")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
            assertThat(manifest.readBytes()).isEqualTo(original)
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `execution time native destination mutation fails before requests`(runtime: ModernRuntime) {
        val project = consumer()
        MemoryRepository(File(project, "execution-mutation-evidence")).use { remote ->
            File(project, "child/build.gradle").appendText(
                """
                def replacement = new URI('${remote.endpoint}')
                tasks.named('exportNexusMavenMavenPub') {
                    doFirst { task ->
                        logger.lifecycle('NEXUS_EXECUTION_DESTINATION_PROBE')
                        task.repository.url = replacement
                        task.repository.allowInsecureProtocol = true
                    }
                }
                """.trimIndent() + "\n"
            )
            val runner = ModernGradleBuild(project, false, runtime)
            repeat(2) { index ->
                val result = runner.failWithDiagnostic("The value of this property has been discarded during serialization.", ":collectNexusMavenBundle1")
                assertThat(result.output).contains("NEXUS_EXECUTION_DESTINATION_PROBE")
                    .contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                    .doesNotContain("NEXUS-COLLECTED :child maven", "NEXUS-NATIVE-EXPORTED")
                assertThat(result.task(":child:exportNexusMavenMavenPub")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.FAILED)
                assertThat(remote.requests).isEmpty()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `native action and input failures never collect a previous successful bundle`(runtime: ModernRuntime) {
        listOf(false, true).forEach { missingInput ->
            val project = consumer()
            val runner = ModernGradleBuild(project, false, runtime)
            runner.storeAndReuse(":collectNexusMavenBundle1")
            File(project, "child/build.gradle").appendText(
                if (missingInput) {
                    """
                    tasks.named('exportNexusMavenMavenPub') {
                        inputs.file(layout.projectDirectory.file('missing-required-input.txt')).withPropertyName('requiredLocalInput')
                        doFirst { logger.lifecycle('UNEXPECTED_NATIVE_ACTION') }
                    }
                    """.trimIndent()
                } else {
                    "tasks.named('exportNexusMavenMavenPub') { doFirst { throw new GradleException('NEXUS_TEST_LOCAL_EXPORT_FAILURE') } }"
                }
            )
            repeat(2) { index ->
                val result = runner.failWithDiagnostic(if (missingInput) "requiredLocalInput" else "NEXUS_TEST_LOCAL_EXPORT_FAILURE", ":collectNexusMavenBundle1")
                assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                    .doesNotContain("NEXUS-COLLECTED :child maven", "NEXUS-NATIVE-EXPORTED", "UNEXPECTED_NATIVE_ACTION")
                assertThat(result.task(":child:exportNexusMavenMavenPub")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.FAILED)
                if (missingInput) assertThat(result.output).contains("does not exist")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `equivalent owned file URI with trailing slash remains local`(runtime: ModernRuntime) {
        val project = consumer()
        File(project, "child/build.gradle").appendText(
            """
            tasks.named('exportNexusMavenMavenPub') {
                repository.url = new URI(repository.url.toString().replaceAll('/+$', '') + '/')
            }
            """.trimIndent()
        )
        ModernGradleBuild(project, false, runtime).storeAndReuse(":collectNexusMavenBundle1") {
            assertThat(it.output).contains("NEXUS-COLLECTED :child maven empty=false")
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `real marker metadata lookalikes and artifact removal preserve neutral identity`(runtime: ModernRuntime) {
        val project = consumer()
        val script = File(project, "child/build.gradle")
        script.appendText(
            """
            apply plugin: 'java-gradle-plugin'
            gradlePlugin { plugins { sample { id = 'probe.sample'; implementationClass = 'probe.Example' } } }
            publishing.publications.mavenPub.artifactId = 'maven-metadata'
            def custom = tasks.register('lookalikeArtifact') {
                def destination = layout.buildDirectory.file('custom-lookalike.xml')
                outputs.file(destination)
                doLast { destination.get().asFile.parentFile.mkdirs(); destination.get().asFile.text = 'custom-artifact' }
            }
            afterEvaluate {
                publishing.publications.mavenPub.artifact(custom) { classifier = 'custom'; extension = 'pom' }
                publishing.publications.create('lateMetadata', org.gradle.api.publish.maven.MavenPublication) {
                    groupId = 'probe'; artifactId = 'lookalike.gradle.plugin'; version = '1.0'
                }
            }
            """.trimIndent() + "\n"
        )
        File(project, "child/src/main/java/probe/Example.java").writeText("package probe; public class Example implements org.gradle.api.Plugin<org.gradle.api.Project> { public void apply(org.gradle.api.Project project) {} }\n")
        val runner = ModernGradleBuild(project, false, runtime)
        val bundle = File(project, "child/build/nexus-publishing/bundles/maven")
        runner.storeAndReuse(":collectNexusMavenBundle1") { result ->
            assertThat(result.output).doesNotContain("NEXUS-RESOLVE :child ivy")
            val model = model(bundle)
            val publications = publications(model)
            assertThat(publications.map { it["name"] }).contains("samplePluginMarkerMaven", "lateMetadata")
            assertThat(publications.single { it["name"] == "samplePluginMarkerMaven" }["kind"]).isEqualTo("metadata-only")
            assertThat(publications.single { it["name"] == "lateMetadata" }["kind"]).isEqualTo("metadata-only")
            val entries = entries(model)
            assertThat(entries.single { it["destination"] == "probe/maven-metadata/1.0/maven-metadata-1.0.jar" }["role"]).isEqualTo("artifact")
            assertThat(entries.single { it["destination"] == "probe/maven-metadata/1.0/maven-metadata-1.0.pom" }["role"]).isEqualTo("pom")
            assertThat(entries.single { it["destination"] == "probe/maven-metadata/1.0/maven-metadata-1.0-custom.pom" }["role"]).isEqualTo("artifact")
        }
        MemoryRepository(File(project, "http-evidence")).use { remote ->
            File(project, "settings.gradle").appendText(
                "\n" + """
                nexusPublishing.useStaging.set(false)
                nexusPublishing.repositories.create('probe') {
                    snapshotRepositoryUrl.set(uri('${remote.endpoint}')); allowInsecureProtocol.set(true)
                }
                """.trimIndent() + "\n"
            )
            var before = 0
            runner.storeAndReuse(":child:publishToProbe") {
                val writes = remote.requests.drop(before).filter { it.first == "PUT" }
                entries(model(bundle)).filter { it["role"] != "checksum" }.forEach { entry ->
                    val path = "/" + entry["destination"]
                    assertThat(writes.count { it.second == path }).isEqualTo(1)
                    assertThat(remote.files[path]).isEqualTo(File(bundle, entry["payload"].toString()).readBytes())
                }
                // Provenance comes from java-gradle-plugin's generated publication, not its name.
                val marker = remote.files.getValue("/probe/sample/probe.sample.gradle.plugin/1.0/probe.sample.gradle.plugin-1.0.pom").toString(Charsets.UTF_8)
                assertThat(marker).contains("<artifactId>probe.sample.gradle.plugin</artifactId>", "<dependencies>", "<artifactId>child</artifactId>")
                assertThat(remote.files["/probe/maven-metadata/1.0/maven-metadata-1.0-custom.pom"]!!.toString(Charsets.UTF_8)).isEqualTo("custom-artifact")
                before = remote.requests.size
            }
            val unrelated = File(project, "child/build/native-control/do-not-delete.txt").apply {
                parentFile.mkdirs()
                writeText("unrelated")
            }
            script.appendText("afterEvaluate { publishing.publications.remove(publishing.publications.lateMetadata); publishing.publications.mavenPub.artifacts.removeAll { it.classifier == 'custom' } }\n")
            runner.storeAndReuse(":collectNexusMavenBundle1", ":child:publishToProbe") {
                val writes = remote.requests.drop(before).filter { it.first == "PUT" }
                assertThat(writes).isNotEmpty()
                assertThat(writes.none { it.second.contains("lookalike.gradle.plugin") || it.second.endsWith("-custom.pom") }).isTrue()
                before = remote.requests.size
                assertThat(publications(model(bundle)).map { it["name"] }).doesNotContain("lateMetadata")
                assertThat(entries(model(bundle)).none { it["classifier"] == "custom" }).isTrue()
                assertThat(File(bundle, "payload/${namespace("lateMetadata")}").exists()).isFalse()
                assertThat(bundle.walkTopDown().filter { it.isFile }.none { it.name.endsWith("-custom.pom") }).isTrue()
                assertThat(unrelated.readText()).isEqualTo("unrelated")
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `typed and extensionless Ivy identities preserve independent native bytes`(runtime: ModernRuntime) {
        val project = consumer()
        val control = directory("typed-native-control")
        val pattern = "[organisation]/[module]/[revision]/[type]/[artifact]-[revision](-[classifier])(.[ext])"
        val producer = """
            apply plugin: 'ivy-publish'
            group = 'probe.tools'; version = '1.0'
            publishing.publications {
                ivyPub(org.gradle.api.publish.ivy.IvyPublication) {
                    module = 'typed'
                    configurations { runtime {} }
                    artifact(file('alpha.txt')) { name = 'shared'; classifier = 'same'; extension = 'txt'; type = 'alpha' }
                    artifact(file('beta.txt')) { name = 'shared'; classifier = 'same'; extension = 'txt'; type = 'beta' }
                    artifact(file('plain.txt')) { name = 'extensionless'; classifier = 'plain'; extension = ''; type = 'plain-type'; conf = 'runtime' }
                    artifact(file('declared.txt')) { name = 'declared'; classifier = 'custom'; extension = 'module'; type = 'ivy'; conf = 'runtime' }
                    descriptor.withXml { asNode().info[0].attributes().put('publication', '20200101000000') }
                }
            }
        """.trimIndent() + "\n"
        listOf(control, File(project, "child")).forEach { directory ->
            listOf("alpha", "beta", "plain", "declared").forEach { File(directory, "$it.txt").writeText("$it bytes\n") }
            File(directory, "build.gradle").writeText(producer)
        }
        File(control, "settings.gradle").writeText("rootProject.name = 'typed'\n")
        File(control, "build.gradle").appendText("publishing.repositories { ivy { name = 'control'; url = layout.buildDirectory.dir('native-control'); patternLayout { artifact '$pattern'; ivy '$pattern'; m2compatible = true } } }\n")
        File(project, "settings.gradle").appendText(
            "\n" + """
            nexusPublishing.repositories.create('typed') {
                publicationType = io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY
                ivyPatternLayout { artifact '$pattern'; ivy '$pattern'; m2compatible = true }
            }
            """.trimIndent() + "\n"
        )
        ModernGradleBuild(control, false, runtime).storeAndReuse("publishIvyPubPublicationToControlRepository")
        val expected = tree(File(control, "build/native-control"))
        val bundle = File(project, "child/build/nexus-publishing/bundles/ivy")
        ModernGradleBuild(project, false, runtime).storeAndReuse(":collectNexusIvyBundle1") {
            val model = model(bundle)
            val entries = entries(model)
            assertThat(entries.associate { it["destination"].toString() to hash(File(bundle, it["payload"].toString())) }).isEqualTo(expected)
            val artifacts = entries.filter { it["role"] == "artifact" }
            assertThat(artifacts).hasSize(4)
            assertThat(artifacts.filter { it["name"] == "shared" }.map { it["type"] }).containsExactlyInAnyOrder("alpha", "beta")
            assertThat(artifacts.single { it["name"] == "extensionless" }["destination"].toString()).endsWith("/extensionless-1.0-plain")
            assertThat(artifacts.single { it["name"] == "declared" }["type"]).isEqualTo("ivy")
            assertThat(entries.none { it["role"] == "gradle-module" }).isTrue()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun model(directory: File): Map<String, Any?> =
        URLClassLoader(arrayOf(ModernTestPaths.candidateJar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec")
                .getMethod("readModel", Path::class.java).invoke(null, directory.toPath()) as Map<String, Any?>
        }

    @Suppress("UNCHECKED_CAST")
    private fun publications(model: Map<String, Any?>) =
        model["publications"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun entries(model: Map<String, Any?>) =
        publications(model).flatMap { it["entries"] as List<Map<String, Any?>> }

    private fun tree(directory: File) =
        directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to hash(it) }
    private fun hash(file: File) =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun namespace(name: String) =
        "p-" + MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun localMetadata(name: String) =
        name.matches(Regex("maven-metadata\\.xml(\\.(md5|sha1|sha256|sha512))?"))
    private fun directory(name: String) =
        File(ModernTestPaths.workspaceRoot, "production-producer/${UUID.randomUUID()}/$name").apply { mkdirs() }

    private fun consumer(): File {
        val project = directory("consumer")
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            rootProject.name = 'producer'
            include ':child', ':empty', ':excluded'
            nexusPublishing.excludedProjects.add(':excluded')
            """.trimIndent()
        )
        File(project, "build.gradle").writeText("")
        listOf("empty", "excluded").forEach { path ->
            File(project, path).mkdirs()
            File(project, "$path/build.gradle").writeText("assert !plugins.hasPlugin('java') && !plugins.hasPlugin('publishing') && !plugins.hasPlugin('signing')\n")
        }
        File(project, "child").mkdirs()
        File(project, "child/build.gradle").writeText(publications())
        source(File(project, "child"))
        return project
    }

    private fun source(project: File, value: Int = 1) {
        File(project, "src/main/java/probe/Example.java").apply {
            parentFile.mkdirs()
            writeText("package probe; public class Example { public static final int VALUE = $value; }\n")
        }
    }

    private fun publications() =
        """
        apply plugin: 'java-library'
        apply plugin: 'maven-publish'
        apply plugin: 'ivy-publish'
        group = 'probe'; version = '1.0'
        tasks.withType(Jar).configureEach { preserveFileTimestamps = false; reproducibleFileOrder = true }
        publishing.publications {
            mavenPub(MavenPublication) { from components.java; artifactId = 'native' }
            ivyPub(IvyPublication) {
                from components.java; module = 'native'
                descriptor.withXml { asNode().info[0].attributes().put('publication', '20200101000000') }
            }
        }
        """.trimIndent() + "\n"
}
