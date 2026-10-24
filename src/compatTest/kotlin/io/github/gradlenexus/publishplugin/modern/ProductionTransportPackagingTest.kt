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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.net.URLClassLoader
import java.util.UUID
import java.util.jar.JarFile

/** Checks the distributable, independent of Gradle's bundled publishing/client libraries. */
internal class ProductionTransportPackagingTest {
    private val pluginPrefix = "io/github/gradlenexus/publishplugin/"
    private val candidate get() = ModernTestPaths.candidateJar

    @Test
    fun `distributable retains descriptors target separation and private dependency namespaces`() {
        JarFile(candidate).use { packaged ->
            val entries = names(packaged)
            assertThat(entries).doesNotHaveDuplicates()
            assertThat(
                entries.filter {
                    it.endsWith(".jar") || it.startsWith("org/gradle/") || it.startsWith("groovy/") ||
                        it.startsWith("org/codehaus/groovy/") || it.startsWith("kotlin/") ||
                        it.startsWith("org/eclipse/aether/") || it.startsWith("org/apache/ivy/") ||
                        it.startsWith("org/apache/http/") || it.startsWith("org/slf4j/") ||
                        it.contains("/transportshadow/") || it.contains("/shadow/org/gradle/") ||
                        it.contains("/shadow/org/eclipse/aether/") || it.contains("/shadow/org/apache/ivy/") ||
                        it.contains("/shadow/org/apache/http/") || it.contains("/shadow/org/slf4j/")
                }
            ).describedAs("no nested archives, Gradle APIs or public transport/runtime copies").isEmpty()
            assertThat(bytes(packaged, "META-INF/gradle-plugins/io.github.gradle-nexus.publish-plugin.properties").toString(Charsets.UTF_8))
                .contains("implementation-class=io.github.gradlenexus.publishplugin.NexusPublishPlugin")
            assertThat(bytes(packaged, "META-INF/gradle-plugins/io.github.gradle-nexus.publish-plugin.internal-producer.properties").toString(Charsets.UTF_8))
                .contains("implementation-class=io.github.gradlenexus.publishplugin.internal.modern.InternalProducerPlugin")
            assertThat(major(bytes(packaged, pluginPrefix + "NexusPublishPlugin.class"))).isEqualTo(52)
            val modernClasses = entries.filter {
                it.endsWith(".class") && (it.startsWith(pluginPrefix + "internal/modern/") || it.startsWith(pluginPrefix + "settings/") || it.startsWith(pluginPrefix + "tasks/"))
            }
            entries.filter { it.endsWith(".class") }.forEach { name ->
                assertThat(major(bytes(packaged, name))).describedAs("Gradle 6.9.4 whole archive ASM scan $name").isLessThanOrEqualTo(59)
            }
            entries.filter { it.endsWith(".class") && it.startsWith(pluginPrefix) && !it.startsWith(pluginPrefix + "shadow/") }.forEach { name ->
                assertThat(major(bytes(packaged, name))).describedAs("Gradle 6.9.4 ASM class scan $name").isEqualTo(52)
            }
            assertThat(modernClasses).isNotEmpty()
            modernClasses.forEach { assertThat(major(bytes(packaged, it))).describedAs("scanner-compatible implementation $it").isEqualTo(52) }
        }
    }

    @Test
    fun `internal session values retain immutable value semantics`() {
        isolated { loader ->
            val cases = listOf(
                Triple("RootSessionBackend\$Request", listOf("endpoint", "mode", "resumeId", "profile", "packageGroup", "description", "descriptionRegex", "connectTimeout", "requestTimeout"), listOf<Any?>(java.net.URI("https://repository.invalid/"), "find", "repository1", "profile1", "org.example", "example:library:1", "example.*", 100, 200)),
                Triple("RootSessionBackend\$Handle", listOf("backend", "destination", "repositoryId"), listOf<Any?>("nexus2", java.net.URI("https://repository.invalid/repository1/"), "repository1"))
            )
            cases.forEach { (name, fields, values) ->
                val type = loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.workflow.$name")
                val members = fields.map { type.getDeclaredField(it) }
                assertThat(java.lang.reflect.Modifier.isFinal(type.modifiers)).isTrue()
                assertThat(members.all { java.lang.reflect.Modifier.isPrivate(it.modifiers) && java.lang.reflect.Modifier.isFinal(it.modifiers) }).isTrue()
                val constructor = type.getDeclaredConstructor(*members.map { it.type }.toTypedArray()).apply { isAccessible = true }
                fun create(arguments: List<Any?>): Any =
                    constructor.newInstance(*arguments.toTypedArray())
                fun call(receiver: Any, method: String, vararg arguments: Any?): Any? =
                    type.getDeclaredMethod(method, *arguments.map { Any::class.java }.toTypedArray()).apply { isAccessible = true }.invoke(receiver, *arguments)
                val value = create(values)
                val equal = create(values)
                assertThat(call(value, "equals", equal)).isEqualTo(true)
                assertThat(call(value, "equals", null)).isEqualTo(false)
                assertThat(call(value, "hashCode")).isEqualTo(call(equal, "hashCode"))
                fields.indices.forEach { index ->
                    val getter = "get" + fields[index].capitalize()
                    assertThat(call(value, getter)).isEqualTo(values[index])
                }
            }
        }
    }

    @Test
    fun `only detached planners and retained legacy libraries load with a JDK parent`() {
        isolated { loader ->
            listOf("org.eclipse.aether.RepositorySystem", "org.apache.ivy.Ivy", "org.apache.http.impl.client.HttpClients", "org.slf4j.LoggerFactory").forEach { name ->
                listOf(name, "io.github.gradlenexus.publishplugin.shadow.$name", "io.github.gradlenexus.publishplugin.transportshadow.$name").forEach { qualified ->
                    assertThatThrownBy { loader.loadClass(qualified) }.isInstanceOf(ClassNotFoundException::class.java)
                }
            }
            listOf("MavenDeployment", "IvyDeployment").forEach { simple ->
                assertThatThrownBy { loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.transport.$simple") }.isInstanceOf(ClassNotFoundException::class.java)
            }
            listOf("MavenPublicationPlan", "IvyPublicationPlan", "DeploymentEndpoint").forEach { simple ->
                val type = loader.loadClass("io.github.gradlenexus.publishplugin.internal.modern.transport.$simple")
                assertThat(File(type.protectionDomain.codeSource.location.toURI()).canonicalFile).isEqualTo(candidate)
            }
            listOf("kotlin.jvm.internal.DefaultConstructorMarker", "retrofit2.Retrofit", "okhttp3.OkHttpClient", "com.google.gson.Gson", "net.jodah.failsafe.Failsafe").forEach { original ->
                val type = loader.loadClass("io.github.gradlenexus.publishplugin.shadow.$original")
                assertThat(File(type.protectionDomain.codeSource.location.toURI()).canonicalFile).isEqualTo(candidate)
                assertThatThrownBy { loader.loadClass(original) }.isInstanceOf(ClassNotFoundException::class.java)
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `native publishing and pattern classes originate in consuming Gradle`(runtime: ModernRuntime) {
        val project = File(ModernTestPaths.workspaceRoot, "native-origin/${UUID.randomUUID()}").apply { mkdirs() }
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            rootProject.name = 'native-origin'
            """.trimIndent() + "\n"
        )
        File(project, "build.gradle").writeText(
            """
            abstract class NativeOrigin extends DefaultTask {
                @Input abstract Property<String> getDistribution()
                @TaskAction void prove() {
                    def adapter = io.github.gradlenexus.publishplugin.internal.modern.transport.GradlePublicationTransport
                    def home = new File(distribution.get()).canonicalFile.toPath()
                    ['org.gradle.api.publish.maven.internal.publisher.MavenPublishers',
                     'org.gradle.api.publish.ivy.internal.publisher.IvyPublisher',
                     'org.gradle.api.internal.artifacts.repositories.resolver.IvyResourcePattern',
                     'org.gradle.api.internal.artifacts.repositories.resolver.ResourcePattern'].each { name ->
                        def nativeType = Class.forName(name, false, adapter.classLoader)
                        def origin = new File(nativeType.protectionDomain.codeSource.location.toURI()).canonicalFile
                        assert origin.toPath().startsWith(home)
                        assert origin.name.endsWith('.jar')
                        logger.lifecycle('NATIVE_GRADLE_ORIGIN ' + name + ' ' + origin)
                    }
                }
            }
            tasks.register('nativeOrigin', NativeOrigin) { distribution.set(gradle.gradleHomeDir.absolutePath) }
            """.trimIndent() + "\n"
        )
        ModernGradleBuild(project, false, runtime).storeAndReuse("nativeOrigin") { result ->
            assertThat(result.output.lineSequence().count { it.startsWith("NATIVE_GRADLE_ORIGIN ") }).isEqualTo(4)
        }
    }

    private fun isolated(check: (ClassLoader) -> Unit) {
        URLClassLoader(arrayOf(candidate.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val thread = Thread.currentThread()
            val previous = thread.contextClassLoader
            try {
                thread.contextClassLoader = loader
                check(loader)
            } finally {
                thread.contextClassLoader = previous
            }
        }
    }

    private fun names(archive: JarFile) =
        archive.entries().asSequence().map { it.name }.toList()
    private fun bytes(archive: JarFile, name: String): ByteArray {
        val entry = requireNotNull(archive.getJarEntry(name)) { "Missing packaged entry $name" }
        return archive.getInputStream(entry).use { it.readBytes() }
    }
    private fun major(bytes: ByteArray) =
        ((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255)
}
