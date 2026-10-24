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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.UUID

/** Signing stays with public native tasks; bundle decoding uses only the packaged implementation. */
internal class ProductionSignedProducerTest {
    private val key by lazy { SoftwareSigningKey() }
    private val ownedProjects = mutableListOf<File>()

    @AfterEach
    fun removeDisposableSigningCaches() {
        ownedProjects.forEach { project ->
            val cache = File(project, ".gradle/configuration-cache")
            check(!cache.exists() || cache.deleteRecursively()) { "Could not remove disposable signing cache: $cache" }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `signed custom artifacts metadata and local prerequisites remain format scoped`(runtime: ModernRuntime) {
        listOf("Maven", "Ivy").forEach { format ->
            val project = consumer()
            File(project, "child/build.gradle").appendText(
                """
                java { withSourcesJar(); withJavadocJar() }
                configurations.apiElements.attributes.attribute(org.gradle.api.attributes.Attribute.of('probe.custom', String), 'custom-module')
                def customFile = layout.buildDirectory.file('special/custom.bin')
                def special = tasks.register('specialArtifact') {
                    outputs.file(customFile)
                    doLast { customFile.get().asFile.parentFile.mkdirs(); customFile.get().asFile.text = 'custom-bytes' }
                }
                publishing.publications.mavenPub { artifact(special) { classifier = 'custom'; extension = 'module' } }
                publishing.publications.ivyPub { artifact(special) { name = 'special-name'; type = 'special-type'; conf = 'runtime'; classifier = 'custom'; extension = 'pom' } }
                tasks.withType(org.gradle.api.publish.maven.tasks.PublishToMavenRepository).configureEach {
                    doFirst { logger.lifecycle('NATIVE_MAVEN_HOOK') }
                }
                tasks.withType(org.gradle.api.publish.ivy.tasks.PublishToIvyRepository).configureEach {
                    doFirst { logger.lifecycle('NATIVE_IVY_HOOK') }
                }
                """.trimIndent() + "\n"
            )
            runner(project, runtime).storeAndReuse(":collectNexus${format}Bundle1") { result ->
                val lower = format.toLowerCase()
                val other = if (format == "Maven") "Ivy" else "Maven"
                assertThat(result.output).contains("NATIVE_${format.toUpperCase()}_HOOK")
                    .doesNotContain("NATIVE_${other.toUpperCase()}_HOOK", "BEGIN PGP PRIVATE KEY BLOCK")
                assertThat(result.output.contains(key.password)).describedAs("software key password absent from logs").isFalse()
                assertThat(result.tasks.none { it.path.contains("${other}Bundle") || it.path.contains("${other.toLowerCase()}Pub") }).isTrue()
                val bundle = File(project, "child/build/nexus-publishing/bundles/$lower")
                val entries = entries(bundle)
                verifySignatures(bundle, entries, 5)
                val metadata = entries.single { it["role"] == "gradle-module" }
                assertThat(payload(bundle, metadata).toString(Charsets.UTF_8)).contains("custom-module")
                val custom = entries.single { it["role"] == "artifact" && it["classifier"] == "custom" }
                assertThat(payload(bundle, custom).toString(Charsets.UTF_8)).isEqualTo("custom-bytes")
                if (format == "Ivy") {
                    assertThat(custom["name"]).isEqualTo("special-name")
                    assertThat(custom["type"]).isEqualTo("special-type")
                    assertThat(custom["conf"]).isEqualTo("runtime")
                }
                assertThat(entries.filter { it["role"] == "artifact" }.map { it["classifier"] }).contains("sources", "javadoc")
                assertThat(File(project, "child/build/nexus-publishing/bundles/${other.toLowerCase()}/manifest.json")).doesNotExist()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `signed snapshots retain logical coordinates and nonauthoritative native metadata`(runtime: ModernRuntime) {
        val project = consumer()
        File(project, "child/build.gradle").appendText("\nversion = '1.0-SNAPSHOT'\n")
        runner(project, runtime).storeAndReuse(":collectNexusMavenBundle1") {
            val bundle = File(project, "child/build/nexus-publishing/bundles/maven")
            val publication = publications(bundle).single()
            assertThat(publication["snapshot"]).isEqualTo(true)
            val entries = entries(bundle)
            verifySignatures(bundle, entries, 3)
            val artifact = entries.single { it["role"] == "artifact" }
            assertThat(artifact["destination"].toString()).endsWith("native-1.0-SNAPSHOT.jar")
            assertThat(artifact["payload"].toString()).matches(".*native-1\\.0-[0-9]{8}\\.[0-9]{6}-[0-9]+\\.jar")
            @Suppress("UNCHECKED_CAST")
            val local = publication["localRepositoryMetadata"] as List<Map<String, Any?>>
            assertThat(local).isNotEmpty()
            assertThat(local.all { it["authoritative"] == false && it["role"] == "local-repository-metadata" }).isTrue()
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `custom settings Ivy layout suppresses module metadata before descriptor signing`(runtime: ModernRuntime) {
        val project = consumer()
        File(project, "settings.gradle").appendText(
            """
            nexusPublishing.repositories.create('custom') {
                publicationType = io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY
                ivyPatternLayout {
                    artifact '[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier])(.[ext])'
                    ivy '[organisation]/[module]/[revision]/ivy-[revision].xml'
                }
            }
            """.trimIndent() + "\n"
        )
        runner(project, runtime).storeAndReuse(":collectNexusIvyBundle1") {
            val bundle = File(project, "child/build/nexus-publishing/bundles/ivy")
            val entries = entries(bundle)
            assertThat(entries.none { it["role"] == "gradle-module" }).isTrue()
            val descriptor = entries.single { it["role"] == "ivy-descriptor" }
            assertThat(payload(bundle, descriptor).toString(Charsets.UTF_8)).doesNotContain("published-with-gradle-metadata")
            verifySignatures(bundle, entries, 2)
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `default Ivy policy preserves user disabled metadata before signing`(runtime: ModernRuntime) {
        val project = consumer()
        File(project, "child/build.gradle").appendText("tasks.named('generateMetadataFileForIvyPubPublication') { enabled=false }\n")
        runner(project, runtime).storeAndReuse(":collectNexusIvyBundle1") { result ->
            assertThat(result.task(":child:generateMetadataFileForIvyPubPublication")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SKIPPED)
            val bundle = File(project, "child/build/nexus-publishing/bundles/ivy")
            val entries = entries(bundle)
            assertThat(entries.none { it["role"] == "gradle-module" }).isTrue()
            assertThat(payload(bundle, entries.single { it["role"] == "ivy-descriptor" }).toString(Charsets.UTF_8)).doesNotContain("published-with-gradle-metadata")
            verifySignatures(bundle, entries, 2)
        }
    }

    private fun verifySignatures(bundle: File, entries: List<Map<String, Any?>>, minimum: Int) {
        val byDestination = entries.associateBy { it["destination"].toString() }
        val signatures = entries.filter { it["role"] == "signature" }
        assertThat(signatures.size).isGreaterThanOrEqualTo(minimum)
        val primaryDestinations = entries.filter { it["role"] in setOf("artifact", "pom", "ivy-descriptor", "gradle-module") }
            .map { it["destination"].toString() }
        assertThat(signatures.map { it["relatedTo"].toString() }).containsExactlyInAnyOrderElementsOf(primaryDestinations)
        signatures.forEach { signature ->
            val target = byDestination[signature["relatedTo"].toString()]!!
            assertThat(key.verifies(payload(bundle, signature), payload(bundle, target)))
                .describedAs("signature verifies unchanged exported bytes for ${target["destination"]}").isTrue()
        }
    }

    private fun payload(bundle: File, entry: Map<String, Any?>) =
        File(bundle, entry["payload"].toString()).readBytes()

    @Suppress("UNCHECKED_CAST")
    private fun publications(bundle: File): List<Map<String, Any?>> =
        URLClassLoader(arrayOf(ModernTestPaths.candidateJar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val model = loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec")
                .getMethod("readModel", Path::class.java).invoke(null, bundle.toPath()) as Map<String, Any?>
            model["publications"] as List<Map<String, Any?>>
        }

    @Suppress("UNCHECKED_CAST")
    private fun entries(bundle: File) =
        publications(bundle).flatMap { it["entries"] as List<Map<String, Any?>> }

    private fun runner(project: File, runtime: ModernRuntime) =
        ModernGradleBuild(
            project,
            false,
            runtime,
            mapOf("BundleFixture_PGP_KEY" to key.armored, "BundleFixture_PGP_PASSWORD" to key.password)
        )

    private fun consumer(): File {
        val project = File(ModernTestPaths.workspaceRoot, "production-signing/${UUID.randomUUID()}/consumer").apply { mkdirs() }
        ownedProjects.add(project)
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            rootProject.name = 'signed-producer'
            include ':child'
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle").writeText("")
        File(project, "child").mkdirs()
        File(project, "child/src/main/java/probe/Example.java").apply {
            parentFile.mkdirs()
            writeText("package probe; /** Example. */ public class Example {}\n")
        }
        File(project, "child/build.gradle").writeText(
            """
            apply plugin: 'java-library'
            apply plugin: 'maven-publish'
            apply plugin: 'ivy-publish'
            group = 'probe'; version = '1.0'
            tasks.withType(Jar).configureEach { preserveFileTimestamps = false; reproducibleFileOrder = true }
            publishing.publications {
                mavenPub(MavenPublication) { from components.java; artifactId = 'native' }
                ivyPub(IvyPublication) { from components.java; module = 'native' }
            }
            apply plugin: 'signing'
            signing {
                useInMemoryPgpKeys(providers.environmentVariable('BundleFixture_PGP_KEY').get(), providers.environmentVariable('BundleFixture_PGP_PASSWORD').get())
                sign publishing.publications
            }
            """.trimIndent() + "\n"
        )
        return project
    }
}
