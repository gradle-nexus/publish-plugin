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
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.UUID

/** Root uploads are compared with separate native controls; signing stays producer-local. */
internal class ProductionSignedIvyWorkflowTest {
    private val key by lazy { SoftwareSigningKey() }
    private val signedProjects = mutableListOf<File>()
    private val typed = "[organisation]/[module]/[revision]/[type]/[artifact]-[revision](-[classifier])(.[ext])"
    private val defaultArtifact = "[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier])(.[ext])"
    private val defaultIvy = "[organisation]/[module]/[revision]/ivy-[revision].xml"

    @AfterEach fun removeDisposableSigningCaches() {
        signedProjects.forEach { project ->
            val cache = File(project, ".gradle/configuration-cache")
            check(!cache.exists() || cache.deleteRecursively()) { "Owned signing cache could not be removed" }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `signed root default and custom Ivy preserve native bytes paths and real consumers`(runtime: ModernRuntime) {
        val controls = nativeControls(runtime, listOf(NativeControl(false), NativeControl(true)))
        listOf(false, true).forEachIndexed { index, custom ->
            val producer = directory()
            publishing(producer, false)
            val native = controls[index]
            MemoryRepository(File(producer, "http-evidence")).use { remote ->
                settings(producer, remote, if (custom) "custom-only" else "default", true, false)
                var before = 0
                runner(producer, runtime).storeAndReuse(":publishToProbe") { result ->
                    noMavenOrSecrets(result)
                    verify(producer, remote, native, custom, true, false, before, runtime)
                    before = remote.requests.size
                }
                consume(remote, runtime, producer, custom, true, false)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `root Ivy fallback typed extensionless artifacts and binary signatures match native`(runtime: ModernRuntime) {
        val producer = directory()
        val control = directory()
        listOf(producer, control).forEach { publishing(it, true) }
        File(control, "settings.gradle").writeText("rootProject.name='native'\n")
        File(control, "build.gradle").appendText("publishing.repositories { ivy { name='control';url=layout.buildDirectory.dir('native-control');${nativeLayout(true,false,true)} } }\n")
        runner(control, runtime).storeAndReuse("publishNativePubPublicationToControlRepository")
        val native = files(File(control, "build/native-control"))
        MemoryRepository(File(producer, "http-evidence")).use { remote ->
            settings(producer, remote, "custom-only", false, true)
            var before = 0
            runner(producer, runtime).storeAndReuse(":publishToProbe") { result ->
                noMavenOrSecrets(result)
                verify(producer, remote, native, true, false, true, before, runtime)
                assertThat(remote.files.keys).contains("/direct/probe.tools/native/1.0/plain-type/extensionless-1.0-plain", "/direct/probe.tools/native/1.0/ivy/declared-1.0-custom.module")
                before = remote.requests.size
            }
            consume(remote, runtime, producer, true, false, true)
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `global custom policy changes signed default descriptors and records independently of selected layout`(runtime: ModernRuntime) {
        val producer = directory()
        publishing(producer, false)
        val modes = listOf("default", "mixed-default", "mixed-custom", "default")
        val controls = nativeControls(runtime, modes.map { NativeControl(it == "mixed-custom", it.startsWith("mixed")) })
        MemoryRepository(File(producer, "http-evidence")).use { remote ->
            var defaultIdentity: String? = null
            var before = 0
            modes.forEachIndexed { modeIndex, mode ->
                val custom = mode == "mixed-custom"
                val global = mode.startsWith("mixed")
                val native = controls[modeIndex]
                settings(producer, remote, mode, true, false)
                repeat(2) { index ->
                    val result = runner(producer, runtime).run(if (custom) ":publishToCustom" else ":publishToProbe")
                    if (index == 1) {
                        assertThat(result.output).contains("Reusing configuration cache.", "Configuration cache entry reused.")
                    } else {
                        assertThat(result.output.contains("Configuration cache entry stored.") || result.output.contains("Configuration cache entry reused.") || result.output.contains("Configuration cache entry updated for no projects, no projects up-to-date.")).isTrue()
                    }
                    noMavenOrSecrets(result)
                    verify(producer, remote, native, custom, true, false, before, runtime, global)
                    val alias = if (custom) "custom" else "probe"
                    val plan = record(producer, alias)
                    assertThat(plan["suppressIvyModuleMetadata"]).isEqualTo(global)
                    if (!custom) {
                        if (defaultIdentity == null) defaultIdentity = plan["layoutPolicy"].toString()
                        assertThat(plan["layoutPolicy"]).isEqualTo(defaultIdentity)
                    } else {
                        assertThat(plan["layoutPolicy"]).isNotEqualTo(defaultIdentity)
                    }
                    before = remote.requests.size
                }
                consume(remote, runtime, producer, custom, true, false, global)
            }
        }
    }

    private data class NativeControl(val custom: Boolean, val global: Boolean = custom)

    /** Each control still publishes signed bytes with Gradle's native publisher into its own repository. */
    private fun nativeControls(runtime: ModernRuntime, layouts: List<NativeControl>): List<Map<String, ByteArray>> {
        val controls = layouts.map { (custom, global) ->
            directory().apply {
                publishing(this, false)
                File(this, "settings.gradle").writeText("rootProject.name='native'\n")
                File(this, "build.gradle").appendText(
                    """
                    publishing.repositories {
                        ivy { name='control';url=layout.buildDirectory.dir('native-control');${nativeLayout(custom,true,false)} }
                        ${if (global && !custom) "ivy { name='unusedCustom';url=layout.buildDirectory.dir('native-unused');${nativeLayout(true,true,false)} }" else ""}
                    }
                    """.trimIndent() + "\n"
                )
            }
        }
        return IndependentGradleBuilds(controls, runtime, mapOf("BundleFixture_PGP_KEY" to key.armored, "BundleFixture_PGP_PASSWORD" to key.password)).use { builds ->
            repeat(2) { index ->
                builds.run(controls.associateWith { listOf(":publishNativePubPublicationToControlRepository") }).forEach { (_, result) ->
                    assertThat(result.output).contains(if (index == 0) "Configuration cache entry stored." else "Configuration cache entry reused.")
                    assertThat(result.task(":publishNativePubPublicationToControlRepository")!!.outcome).isEqualTo(org.gradle.testkit.runner.TaskOutcome.SUCCESS)
                    noMavenOrSecrets(result)
                }
            }
            controls.map { files(File(it, "build/native-control")) }
        }
    }

    private fun directory() =
        File(ModernTestPaths.workspaceRoot, "production-ivy-signing/${UUID.randomUUID()}/consumer").apply {
            mkdirs()
            signedProjects.add(this)
        }
    private fun runner(project: File, runtime: ModernRuntime) =
        ModernGradleBuild(project, false, runtime, mapOf("BundleFixture_PGP_KEY" to key.armored, "BundleFixture_PGP_PASSWORD" to key.password))
    private fun nativeLayout(custom: Boolean, m2: Boolean, fallback: Boolean) =
        if (custom) "patternLayout { artifact '$typed';artifact 'unused/[artifact].[ext]';${if (fallback) "" else "ivy '$typed';ivy 'unused-ivy/[artifact].[ext]';"}m2compatible=$m2 }" else "layout 'maven'"
    private fun settings(project: File, remote: MemoryRepository, mode: String, m2: Boolean, fallback: Boolean) {
        val customProbe = mode == "custom-only"
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            rootProject.name='native'
            nexusPublishing.useStaging.set(false)
            nexusPublishing.repositories.create('probe') {
                publicationType.set(io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY)
                snapshotRepositoryUrl.set(uri('${remote.endpoint}direct'))
                allowInsecureProtocol.set(true)
                ${if (customProbe) "ivyPatternLayout { artifact '$typed';artifact 'unused/[artifact].[ext]';${if (fallback) "" else "ivy '$typed';ivy 'unused-ivy/[artifact].[ext]';"}m2compatible=$m2 }" else ""}
            }
            ${if (mode.startsWith("mixed")) {
                """
            nexusPublishing.repositories.create('custom') {
                publicationType.set(io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY)
                snapshotRepositoryUrl.set(uri('${remote.endpoint}direct'))
                allowInsecureProtocol.set(true)
                ivyPatternLayout { artifact '$typed';artifact 'unused/[artifact].[ext]';ivy '$typed';ivy 'unused-ivy/[artifact].[ext]';m2compatible=true }
            }
            """
            } else {
                ""
            }}
            """.trimIndent() + "\n"
        )
    }
    private fun publishing(project: File, binary: Boolean) {
        val group = if (binary) "probe.tools" else "probe"
        File(project, "build.gradle").writeText(
            """
            apply plugin:'java-library';apply plugin:'ivy-publish';apply plugin:'maven-publish';apply plugin:'signing'
            group='$group';version='1.0';base.archivesName='native'
            java { withSourcesJar();withJavadocJar() }
            tasks.withType(Jar).configureEach { preserveFileTimestamps=false;reproducibleFileOrder=true }
            tasks.withType(Javadoc).configureEach { options.noTimestamp=true }
            configurations.apiElements.attributes.attribute(org.gradle.api.attributes.Attribute.of('probe.custom',String),'root-module')
            def classified=tasks.register('classifiedArtifact',Jar) { archiveClassifier='linux.x86' }
            publishing.publications {
                nativePub(IvyPublication) {
                    from components.java;module='native'
                    configurations.maybeCreate('runtime')
                    artifact(classified) { name='special';type='special-type';conf='runtime';classifier='linux.x86' }
                    descriptor.withXml { asNode().info[0].attributes().put('publication','20200101000000');asNode().info[0].appendNode('description','root-custom') }
                    ${if (binary) "artifact(file('plain-payload')) { name='extensionless';classifier='plain';extension='';type='plain-type';conf='runtime' };artifact(file('plain-payload')) { name='declared';classifier='custom';extension='module';type='ivy';conf='runtime' }" else ""}
                }
                mavenPub(MavenPublication) { from components.java;artifactId='native-maven' }
            }
            signing {
                ${if (binary) "signatureTypes.defaultType='sig'" else ""}
                useInMemoryPgpKeys(providers.environmentVariable('BundleFixture_PGP_KEY').get(),providers.environmentVariable('BundleFixture_PGP_PASSWORD').get())
                sign publishing.publications
            }
            """.trimIndent() + "\n"
        )
        File(project, "plain-payload").writeText("declared payload\n")
        File(project, "src/main/java/probe/Example.java").apply {
            parentFile.mkdirs()
            writeText("package probe; /** Example. */ public class Example { public static int value() { return 1; } }\n")
        }
    }
    private fun files(directory: File) =
        directory.walkTopDown().filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes() }
    private fun noMavenOrSecrets(result: org.gradle.testkit.runner.BuildResult) {
        assertThat(result.tasks.none { it.path.contains("Maven") }).isTrue()
        assertThat(result.output).doesNotContain("BEGIN PGP PRIVATE KEY BLOCK")
        assertThat(result.output.contains(key.password)).describedAs("software password absent from logs").isFalse()
    }
    private fun record(project: File, alias: String): Map<*, *> {
        val identity = project.toPath().toAbsolutePath().normalize().toString() + "\u0000" + alias
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        return Gson().fromJson(File(project, "build/nexus-publishing/workflows/nexus_$hash/plan.json").readText(), Map::class.java)
    }
    private fun verify(project: File, remote: MemoryRepository, native: Map<String, ByteArray>, custom: Boolean, m2: Boolean, binary: Boolean, before: Int, runtime: ModernRuntime, global: Boolean = custom) {
        val bundle = File(project, "build/nexus-publishing/bundles/ivy")
        Api().use { api ->
            val entries = api.entries(bundle)
            val primary = entries.filter { it["role"] in setOf("artifact", "ivy-descriptor", "gradle-module") }
            val signatures = entries.filter { it["role"] == "signature" }
            assertThat(primary).isNotEmpty()
            assertThat(signatures.map { it["relatedTo"] }).containsExactlyInAnyOrderElementsOf(primary.map { it["destination"] })
            signatures.forEach { signature ->
                val target = entries.single { it["destination"] == signature["relatedTo"] }
                assertThat(key.verifies(File(bundle, signature["payload"].toString()).readBytes(), File(bundle, target["payload"].toString()).readBytes())).describedAs("unchanged root payload signature verifies").isTrue()
            }
            val descriptor = entries.single { it["role"] == "ivy-descriptor" }
            val descriptorText = File(bundle, descriptor["payload"].toString()).readText()
            assertThat(descriptorText.contains("published-with-gradle-metadata")).isEqualTo(!global)
            assertThat(entries.any { it["role"] == "gradle-module" }).isEqualTo(!global)
            val artifacts = if (custom) listOf(typed, "unused/[artifact].[ext]") else listOf(defaultArtifact)
            val descriptors = if (custom) if (binary) emptyList() else listOf(typed, "unused-ivy/[artifact].[ext]") else listOf(defaultIvy)
            val payloads = NativeIvyPlanEvidence.plan(listOf(bundle.toPath()), artifacts, descriptors, m2, global, runtime).mapValues { it.value.file }
            assertThat(payloads.keys).containsExactlyInAnyOrderElementsOf(native.keys)
            val writes = remote.requests.drop(before).filter { it.first == "PUT" }.map { it.second }
            assertThat(writes).containsExactlyInAnyOrderElementsOf(native.keys.map { "/direct/$it" })
            assertThat(writes).doesNotHaveDuplicates()
            payloads.forEach { (path, file) -> assertThat(remote.files["/direct/$path"]).describedAs("root transmitted exact prepared $path").isEqualTo(file.readBytes()) }
            val suffix = if (binary) "sig" else "asc"
            native.filterKeys { !it.endsWith(".$suffix") && !it.matches(Regex(".*\\.$suffix\\.(md5|sha1|sha256|sha512)")) }.forEach { (path, bytes) ->
                assertThat(remote.files["/direct/$path"]).describedAs("independent native unsigned $path").isEqualTo(bytes)
            }
        }
    }
    private fun consume(remote: MemoryRepository, runtime: ModernRuntime, producer: File, custom: Boolean, m2: Boolean, binary: Boolean, global: Boolean = custom) {
        val consumer = File(ModernTestPaths.workspaceRoot, "production-ivy-consumer/${UUID.randomUUID()}/consumer").apply { mkdirs() }
        File(consumer, "settings.gradle").writeText("rootProject.name='ivy-consumer'\n")
        val group = if (binary) "probe.tools" else "probe"
        val layout = if (custom) "patternLayout { artifact '$typed';ivy '$typed';m2compatible=$m2 }" else "layout 'maven'"
        File(consumer, "build.gradle").writeText(
            """
            plugins { id 'java-library' }
            repositories { ivy { url=uri('${remote.endpoint}direct/');allowInsecureProtocol=true;$layout;metadataSources { ${if (global) "ivyDescriptor()" else "gradleMetadata()"} } } }
            dependencies { implementation '$group:native:1.0' }
            tasks.register('resolveArtifact',Sync) { from configurations.compileClasspath;into layout.buildDirectory.dir('resolved') }
            """.trimIndent() + "\n"
        )
        val before = remote.requests.size
        ModernGradleBuild(consumer, false, runtime, isolatedDependencyCache = true).storeAndReuse("resolveArtifact")
        val jar = File(consumer, "build/resolved").listFiles()!!.single { it.name == "native-1.0.jar" }
        assertThat(jar.readBytes()).isEqualTo(File(producer, "build/libs/native-1.0.jar").readBytes())
        java.util.jar.JarFile(jar).use { assertThat(it.getJarEntry("probe/Example.class")).isNotNull() }
        val suffix = if (global) ".xml" else ".module"
        assertThat(remote.requests.drop(before).any { it.first == "GET" && it.second.endsWith(suffix) && remote.files.containsKey(it.second) }).isTrue()
    }
    private class Api : java.io.Closeable {
        private val loader = URLClassLoader(arrayOf(ModernTestPaths.candidateJar.toURI().toURL()), ClassLoader.getPlatformClassLoader())

        @Suppress("UNCHECKED_CAST")
        fun entries(bundle: File): List<Map<String, Any?>> {
            val model = loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.bundle.BundleCodec").getMethod("readModel", Path::class.java).invoke(null, bundle.toPath()) as Map<String, Any?>
            return (model["publications"] as List<Map<String, Any?>>).flatMap { it["entries"] as List<Map<String, Any?>> }
        }

        override fun close() =
            loader.close()
    }
}
