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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.Closeable
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/** Packaged root publication with independent native controls; JDK inspection is detached only. */
internal class ProductionTransportTest {
    private val key by lazy { SoftwareSigningKey() }
    private val signedProjects = mutableListOf<File>()

    @AfterEach
    fun removeOwnedSigningCaches() {
        signedProjects.forEach { project ->
            val cache = File(project, ".gradle/configuration-cache")
            check(!cache.exists() || cache.deleteRecursively()) { "Could not remove disposable signing cache" }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `Maven signed native release and seeded snapshot history remain faithful`(runtime: ModernRuntime) {
        listOf("1.0", "1.0-SNAPSHOT").forEach { version ->
            val project = producer(version, "Maven")
            val control = nativeControl(version, "Maven")
            val native = ModernGradleBuild(control, false, runtime, signingEnvironment())
            native.storeAndReuse("publishNativePubPublicationToControlRepository")
            val expected = publicationBytes(File(control, "build/native-control"), version)
            val bundle = bundle(project)
            val build = ModernGradleBuild(project, false, runtime, signingEnvironment())
            MemoryRepository(File(project, "http-evidence")).use { remote ->
                remote.seed(version)
                if (version.endsWith("SNAPSHOT")) {
                    File(control, "build.gradle").appendText("\npublishing.repositories { maven { name = 'seed'; url = uri('${remote.endpoint}'); allowInsecureProtocol = true } }\n")
                    native.storeAndReuse("publishNativePubPublicationToSeedRepository")
                    assertThat(metadata(remote, version)).contains("<buildNumber>9</buildNumber>")
                }
                File(project, "settings.gradle").appendText(
                    """
                    nexusPublishing.useStaging.set(false)
                    nexusPublishing.repositories.create('probe') {
                        snapshotRepositoryUrl.set(uri('${remote.endpoint}')); allowInsecureProtocol.set(true)
                    }
                    """.trimIndent() + "\n"
                )
                var before = 0
                build.storeAndReuse(":publishToProbe") { result ->
                    assertThat(result.output).doesNotContain("NEXUS-RESOLVE : ivy")
                    assertThat(remote.requests.drop(before).any { it.first == "PUT" }).isTrue()
                    verifyMaven(remote, bundle, version, expected)
                    before = remote.requests.size
                }
                resolve(remote, runtime, version, "Maven", false, bundle)
                if (version.endsWith("SNAPSHOT")) {
                    assertThat(metadata(remote, version)).contains("<buildNumber>11</buildNumber>")
                    val previous = remote.files.filterKeys { it.endsWith("-11.jar") }.mapValues { it.value.copyOf() }
                    source(project, 2)
                    val changed = build.run(":publishToProbe")
                    assertThat(changed.output).contains("Configuration cache entry reused.")
                    verifyMaven(remote, bundle, version, null)
                    assertThat(metadata(remote, version)).contains("<buildNumber>12</buildNumber>")
                    assertThat(remote.files["/probe/native/$version/native-1.0-20200101.000000-7.jar"]).isEqualTo(byteArrayOf(1, 2, 3))
                    previous.forEach { (path, bytes) -> assertThat(remote.files[path]).isEqualTo(bytes) }
                    assertThat(remote.files.entries.single { it.key.endsWith("-12.jar") }.value).isNotEqualTo(previous.values.single())
                    resolve(remote, runtime, version, "Maven", false, bundle)
                }
            }
        }
    }

    @Test
    fun `packaged modern libraries preserve actual legacy retry logging on old and current runtimes`() {
        listOf(false, true).forEach { current ->
            val project = directory("legacy-control")
            File(project, "settings.gradle").writeText("rootProject.name = 'legacy'\n")
            File(project, "build.gradle").writeText(
                """
                buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
                apply plugin: 'io.github.gradle-nexus.publish-plugin'
                assert extensions.findByName('nexusPublishing') != null
                tasks.register('retryProbe') {
                    doLast {
                        def count = new java.util.concurrent.atomic.AtomicInteger()
                        def stop = { value -> value == 'NEXUS_SYNTHETIC_RETRY' } as io.github.gradlenexus.publishplugin.shadow.kotlin.jvm.functions.Function1
                        def action = { -> count.incrementAndGet() == 1 ? 'NEXUS_SYNTHETIC_RETRY' : 'done' } as io.github.gradlenexus.publishplugin.shadow.kotlin.jvm.functions.Function0
                        def retrier = new io.github.gradlenexus.publishplugin.internal.BasicActionRetrier(1, java.time.Duration.ofMillis(1), stop)
                        assert retrier.execute(action) == 'done'
                        assert count.get() == 2
                    }
                }
                """.trimIndent() + "\n"
            )
            runLegacy(project, "retryProbe", current = current, info = true)
            assertThat(File(project, "legacy-${if (current) "8.7" else "6.2.2"}.log").readText()).contains("Attempt 1/2 failed with result: NEXUS_SYNTHETIC_RETRY")
        }
    }

    @Test
    fun `credential bearing insecure and invalid endpoints are rejected without leaking values`() {
        val sentinel = "credential-" + UUID.randomUUID()
        TransportApi().use { api ->
            listOf("http://user:$sentinel@127.0.0.1/", "http://127.0.0.1/?token=$sentinel", "http://127.0.0.1/#$sentinel").forEach { value ->
                assertThatThrownBy { api.endpoint(value) }.hasMessage("NEXUS_CREDENTIAL_BEARING_ENDPOINT_REJECTED")
            }
            assertThatThrownBy { api.endpoint("http://127.0.0.1/", false) }.hasMessage("NEXUS_INSECURE_HTTP_REJECTED")
            listOf("ftp://127.0.0.1/", "http://127.0.0.1/../other/", "/relative/", "relative", "//127.0.0.1/").forEach { value ->
                assertThatThrownBy { api.endpoint(value) }.hasMessage("NEXUS_INVALID_ENDPOINT")
            }
        }
    }

    private val typedPattern = "[organisation]/[module]/[revision]/[type]/[artifact]-[revision](-[classifier])(.[ext])"

    private fun directory(name: String) =
        File(ModernTestPaths.workspaceRoot, "production-transport/${UUID.randomUUID()}/$name").apply { mkdirs() }
    private fun bundle(project: File) =
        File(project, "build/nexus-publishing/bundles/maven")
    private fun signingEnvironment() =
        mapOf("BundleFixture_PGP_KEY" to key.armored, "BundleFixture_PGP_PASSWORD" to key.password)
    private fun source(project: File, value: Int = 1) {
        File(project, "src/main/java/probe/Example.java").apply {
            parentFile.mkdirs()
            writeText("package probe; public class Example { public static int value() { return $value; } }\n")
        }
    }
    private fun script(version: String, format: String) =
        """
        apply plugin: 'java-library'
        apply plugin: '${format.toLowerCase()}-publish'
        apply plugin: 'signing'
        group = 'probe'; version = '$version'; base.archivesName = 'native'
        tasks.withType(Jar).configureEach { preserveFileTimestamps = false; reproducibleFileOrder = true }
        def classified = tasks.register('classifiedArtifact', Jar) { archiveClassifier = 'linux.x86' }
        publishing.publications {
            nativePub(org.gradle.api.publish.${format.toLowerCase()}.${format}Publication) {
                ${if (format == "Maven") "artifactId" else "module"} = 'native'
                from components.java
                ${if (format == "Maven") "artifact classified; pom { name = 'signed-custom' }" else "artifact(classified) { name = 'special'; type = 'special-type'; conf = 'runtime'; classifier = 'linux.x86' }; descriptor.withXml { asNode().info[0].attributes().put('publication', '20200101000000') }"}
            }
        }
        signing { useInMemoryPgpKeys(providers.environmentVariable('BundleFixture_PGP_KEY').get(), providers.environmentVariable('BundleFixture_PGP_PASSWORD').get()); sign publishing.publications }
        """.trimIndent() + "\n"

    private fun producer(version: String, format: String, custom: Boolean = false): File {
        val project = directory("producer")
        signedProjects.add(project)
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            rootProject.name = 'native'
            ${if (custom) "nexusPublishing.repositories.create('custom') { publicationType = io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY; ivyPatternLayout { artifact '$typedPattern'; ivy '$typedPattern'; m2compatible = true } }" else ""}
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle").writeText(script(version, format))
        source(project)
        return project
    }
    private fun nativeControl(version: String, format: String, custom: Boolean = false): File {
        val control = directory("native-control")
        signedProjects.add(control)
        File(control, "settings.gradle").writeText("rootProject.name = 'native'\n")
        File(control, "build.gradle").writeText(
            script(version, format) + """
            publishing.repositories {
                ${format.toLowerCase()} { name = 'control'; url = layout.buildDirectory.dir('native-control'); ${if (format == "Ivy") if (custom) "patternLayout { artifact '$typedPattern'; artifact 'unused/[artifact].[ext]'; ivy '$typedPattern'; ivy 'unused-ivy/[artifact].[ext]'; m2compatible = true }" else "layout 'maven'" else ""} }
            }
            """.trimIndent() + "\n"
        )
        source(control)
        return control
    }

    private fun metadata(remote: MemoryRepository, version: String) =
        remote.files["/probe/native/$version/maven-metadata.xml"]!!.toString(Charsets.UTF_8)

    private fun publicationBytes(directory: File, version: String): Map<String, ByteArray> =
        directory.walkTopDown().filter { it.isFile }.filterNot {
            it.name.startsWith("maven-metadata") || it.name.endsWith(".asc") || it.name.matches(Regex(".*\\.(md5|sha1|sha256|sha512)"))
        }.associate { file ->
            val logical = if (version.endsWith("SNAPSHOT")) file.name.replace(Regex("native-1\\.0-[0-9]{8}\\.[0-9]{6}-[0-9]+"), "native-1.0-SNAPSHOT") else file.name
            logical to file.readBytes()
        }

    private fun verifyMaven(remote: MemoryRepository, directory: File, version: String, native: Map<String, ByteArray>?) {
        TransportApi().use { api ->
            api.verifyDetachedMavenPlan(directory)
            val entries = api.entries(directory)
            val remoteVersion = if (version.endsWith("SNAPSHOT")) {
                val metadata = metadata(remote, version)
                val timestamp = Regex("<timestamp>([^<]+)</timestamp>").find(metadata)!!.groupValues[1]
                val number = Regex("<buildNumber>([^<]+)</buildNumber>").find(metadata)!!.groupValues[1]
                "1.0-$timestamp-$number"
            } else {
                version
            }
            entries.filter { it["role"] != "checksum" }.forEach { entry ->
                val logical = entry["destination"].toString()
                val slash = logical.lastIndexOf('/')
                val filename = logical.substring(slash + 1)
                val remotePath = "/" + logical.substring(0, slash + 1) + filename.replace("native-$version", "native-$remoteVersion")
                val payload = File(directory, entry["payload"].toString()).readBytes()
                assertThat(remote.files[remotePath]).describedAs("unchanged prepared Maven payload $remotePath").isEqualTo(payload)
                if (entry["role"] != "signature" && native != null) assertThat(payload).isEqualTo(native[filename])
                if (entry["role"] == "signature") {
                    val target = entries.single { it["destination"] == entry["relatedTo"] }
                    assertThat(key.verifies(payload, File(directory, target["payload"].toString()).readBytes())).isTrue()
                }
                listOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512").forEach { (suffix, algorithm) ->
                    remote.files["$remotePath.$suffix"]?.let { checksum ->
                        assertThat(checksum.toString(Charsets.UTF_8).trim()).isEqualTo(digest(payload, algorithm))
                    }
                }
            }
            val artifactMetadata = remote.files["/probe/native/maven-metadata.xml"]!!.toString(Charsets.UTF_8)
            assertThat(artifactMetadata).contains("<version>0.9</version>", "<version>$version</version>")
        }
    }

    private fun resolve(remote: MemoryRepository, runtime: ModernRuntime, version: String, format: String, custom: Boolean, bundle: File, ivyLayout: String? = null, group: String = "probe") {
        val consumer = directory("fresh-consumer")
        File(consumer, "settings.gradle").writeText("rootProject.name = 'consumer'\n")
        val repository = if (format == "Maven") {
            "maven { url = uri('${remote.endpoint}'); allowInsecureProtocol = true; metadataSources { gradleMetadata() } }"
        } else {
            "ivy { url = uri('${remote.endpoint}'); allowInsecureProtocol = true; ${if (custom) (ivyLayout ?: "patternLayout { artifact '$typedPattern'; ivy '$typedPattern'; m2compatible = true }") + "; metadataSources { ivyDescriptor() }" else "layout 'maven'; metadataSources { gradleMetadata() }"} }"
        }
        File(consumer, "build.gradle").writeText(
            """
            plugins { id 'java-library' }
            repositories { $repository }
            dependencies { implementation '$group:native:$version' }
            tasks.register('resolveArtifact', Sync) { from configurations.compileClasspath; into layout.buildDirectory.dir('resolved') }
            """.trimIndent() + "\n"
        )
        val before = remote.requests.size
        ModernGradleBuild(consumer, false, runtime, isolatedDependencyCache = true).storeAndReuse("resolveArtifact")
        val resolved = File(consumer, "build/resolved").listFiles()!!.filter { it.extension == "jar" && !it.name.contains("linux.x86") && !it.name.startsWith("special-") }.single()
        TransportApi().use { api ->
            val main = api.entries(bundle).single { it["role"] == "artifact" && it["classifier"] == "" && it["extension"] == "jar" }
            assertThat(resolved.readBytes()).describedAs("fresh consumer resolves the current prepared primary Jar").isEqualTo(File(bundle, main["payload"].toString()).readBytes())
        }
        java.util.jar.JarFile(resolved).use { archive -> assertThat(archive.getJarEntry("probe/Example.class")).isNotNull() }
        val metadataSuffix = if (custom) ".xml" else ".module"
        assertThat(remote.requests.drop(before).any { it.first == "GET" && it.second.endsWith(metadataSuffix) && remote.files.containsKey(it.second) }).isTrue()
    }

    private fun digest(bytes: ByteArray, algorithm: String = "SHA-256") =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Endpoint and BundleCodec have no Gradle/service/native-publisher dependency. */
    private class TransportApi : Closeable {
        private val loader = URLClassLoader(arrayOf(ModernTestPaths.candidateJar.toURI().toURL()), ClassLoader.getPlatformClassLoader())
        private val prefix = "io.github.gradlenexus.publishplugin.internal.modern."
        fun endpoint(value: String, insecure: Boolean = true): Any =
            call(loader.loadClass(prefix + "transport.DeploymentEndpoint").getMethod("validate", String::class.java, Boolean::class.javaPrimitiveType), value, insecure)

        fun verifyDetachedMavenPlan(directory: File) {
            val type = loader.loadClass(prefix + "transport.MavenPublicationPlan")
            val plan = type.getMethod("plan", List::class.java).invoke(type.getField("INSTANCE").get(null), listOf(directory.toPath()))

            @Suppress("UNCHECKED_CAST")
            val publications = plan.javaClass.getMethod("getPublications").invoke(plan) as MutableList<Any>
            assertThatThrownBy { publications.clear() }.isInstanceOf(UnsupportedOperationException::class.java)
            assertThat(plan.javaClass.declaredFields.none { it.type == ByteArray::class.java || it.type.name.startsWith("org.gradle.") }).isTrue()
        }

        @Suppress("UNCHECKED_CAST")
        fun entries(directory: File): List<Map<String, Any?>> {
            val model = call(loader.loadClass(prefix + "bundle.BundleCodec").getMethod("readModel", Path::class.java), directory.toPath()) as Map<String, Any?>
            return (model["publications"] as List<Map<String, Any?>>).flatMap { it["entries"] as List<Map<String, Any?>> }
        }
        private fun call(method: java.lang.reflect.Method, vararg args: Any?): Any {
            try {
                return method.invoke(null, *args) ?: Unit
            } catch (failed: InvocationTargetException) {
                throw failed.targetException
            }
        }
        override fun close() =
            loader.close()
    }
}
