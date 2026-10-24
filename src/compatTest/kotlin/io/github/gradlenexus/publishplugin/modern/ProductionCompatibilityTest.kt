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
import org.gradle.api.Project
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.Properties
import java.util.UUID
import java.util.jar.JarFile
import javax.tools.ToolProvider

/** Production consumers exercise the packaged plugin and its fixed public JVM contract. */
class ProductionCompatibilityTest {
    @Test
    fun `packaged entry retains original binary members and typed Project contract`() {
        val expected = javaClass.getResourceAsStream("/legacy-entry-api.txt")!!.bufferedReader().use { it.readLines() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('|', limit = 2) }
            .groupBy({ it[0] }, { it[1] })
        expected.forEach { (owner, members) ->
            val missing = members.toSet() - publicMembers(ModernTestPaths.candidateJar, owner)
            assertThat(missing)
                .describedAs("Public binary descriptors for $owner, including compiler bridges")
                .isEmpty()
        }
        JarFile(ModernTestPaths.candidateJar).use { jar ->
            val entry = ClassReader(jar.getInputStream(jar.getJarEntry("$ENTRY.class")))
            assertThat(entry.readShort(6).toInt()).isEqualTo(52)
            entry.accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                        assertThat(signature).contains("Lorg/gradle/api/Plugin<Lorg/gradle/api/Project;>;")
                    }
                },
                ClassReader.SKIP_CODE
            )
            val descriptor = Properties().apply {
                jar.getInputStream(jar.getJarEntry("META-INF/gradle-plugins/$PLUGIN_ID.properties")).use { load(it) }
            }
            assertThat(descriptor.getProperty("implementation-class")).isEqualTo(ENTRY.replace('/', '.'))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `typed direct and erased Project calls execute production legacy route`(current: Boolean) {
        val project = project("typed-calls")
        compileJava(
            project,
            "ProjectCaller",
            ModernTestPaths.candidateJar,
            """
            import org.gradle.api.Plugin;
            import org.gradle.api.Project;
            import io.github.gradlenexus.publishplugin.NexusPublishPlugin;
            public class ProjectCaller {
                public static void apply(Project project, boolean direct) {
                    NexusPublishPlugin plugin = new NexusPublishPlugin();
                    Plugin<Project> typed = plugin;
                    NexusPublishPlugin.Companion companion = NexusPublishPlugin.Companion;
                    if (companion == null) throw new AssertionError();
                    if (direct) plugin.apply(project); else typed.apply(project);
                }
            }
            """.trimIndent()
        )
        listOf(false, true).forEach { direct ->
            File(project, "build.gradle").writeText(
                """
                ${classpath("caller-classes")}
                ProjectCaller.apply(project, $direct)
                assert extensions.findByName('nexusPublishing') != null
                assert gradle.sharedServices.registrations.findByName('stagingRepositoryUrlRegistry') != null
                assert System.getProperty('java.specification.version') == findProperty('expectedJava')
                """.trimIndent() + "\n"
            )
            runLegacy(project, "closeStagingRepositories", current)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `fresh typed Java lookup and public plugin ID execute production legacy route`(current: Boolean) {
        val project = project("public-id")
        compileJava(
            project,
            "TypedLookup",
            ModernTestPaths.candidateJar,
            """
            import org.gradle.api.Plugin;
            import org.gradle.api.Project;
            import io.github.gradlenexus.publishplugin.NexusPublishPlugin;
            public class TypedLookup {
                public static NexusPublishPlugin lookup(Project project) {
                    Plugin<Project> plugin = project.getPlugins().getPlugin(NexusPublishPlugin.class);
                    if (plugin != project.getPlugins().getPlugin(NexusPublishPlugin.class)) throw new AssertionError();
                    return project.getPlugins().getPlugin(NexusPublishPlugin.class);
                }
            }
            """.trimIndent()
        )
        File(project, "build.gradle").writeText(
            """
            ${classpath("caller-classes")}
            apply plugin: '$PLUGIN_ID'
            assert TypedLookup.lookup(project) != null
            assert extensions.getByName('nexusPublishing').class.name.startsWith('io.github.gradlenexus.publishplugin.NexusPublishExtension')
            assert io.github.gradlenexus.publishplugin.NexusPublishPlugin.Companion != null
            """.trimIndent() + "\n"
        )
        runLegacy(project, "closeStagingRepositories", current)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Groovy and Kotlin source calls retain production Project entry`(current: Boolean) {
        val groovy = project("groovy")
        File(groovy, "build.gradle").writeText(
            """
            ${classpath()}
            org.gradle.api.Plugin<org.gradle.api.Project> typed = new io.github.gradlenexus.publishplugin.NexusPublishPlugin()
            typed.apply(project)
            assert extensions.findByName('nexusPublishing') != null
            """.trimIndent() + "\n"
        )
        runLegacy(groovy, "closeStagingRepositories", current)
        val kotlin = project("kotlin")
        File(kotlin, "build.gradle.kts").writeText(
            """
            buildscript { dependencies { classpath(files("${ModernTestPaths.candidateJar.invariantSeparatorsPath}")) } }
            val typed: Plugin<Project> = io.github.gradlenexus.publishplugin.NexusPublishPlugin()
            val projectApply = io.github.gradlenexus.publishplugin.NexusPublishPlugin()::apply
            projectApply(project)
            check(extensions.findByName("nexusPublishing") != null)
            check(typed.javaClass.name == "io.github.gradlenexus.publishplugin.NexusPublishPlugin")
            """.trimIndent() + "\n"
        )
        runLegacy(kotlin, "closeStagingRepositories", current)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `direct child application retains original exception before legacy wiring`(current: Boolean) {
        val project = project("child-diagnostic")
        File(project, "child").mkdirs()
        File(project, "settings.gradle").appendText("include 'child'\n")
        File(project, "build.gradle").writeText(
            """
            ${classpath()}
            def child = project(':child')
            try {
                new io.github.gradlenexus.publishplugin.NexusPublishPlugin().apply(child)
                throw new AssertionError('Child application unexpectedly succeeded')
            } catch (IllegalArgumentException expected) {
                assert expected.message == 'Plugin must be applied to the root project but was applied to :child'
            }
            assert child.extensions.findByName('nexusPublishing') == null
            assert gradle.sharedServices.registrations.findByName('stagingRepositoryUrlRegistry') == null
            """.trimIndent() + "\n"
        )
        runLegacy(project, "help", current)
    }

    private fun project(name: String): File =
        File(ModernTestPaths.workspaceRoot, "production-compatibility/${UUID.randomUUID()}/$name").apply {
            mkdirs()
            File(this, "settings.gradle").writeText("rootProject.name = 'production-compatibility'\n")
        }

    private fun classpath(extra: String? = null): String =
        "buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}'" +
            (if (extra == null) "" else ", '$extra'") + ") } }"

    private fun compileJava(project: File, name: String, pluginJar: File, source: String) {
        val javaFile = File(project, "$name.java").apply { writeText(source + "\n") }
        val classes = File(project, "caller-classes").apply { mkdirs() }
        val api = File(Project::class.java.protectionDomain.codeSource.location.toURI())
        val groovy = File(groovy.lang.GroovyObject::class.java.protectionDomain.codeSource.location.toURI())
        assertThat(
            ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "--release", "8", "-Xlint:all", "-Werror", "-classpath",
                listOf(pluginJar, api, groovy).joinToString(File.pathSeparator), "-d", classes.path, javaFile.path
            )
        ).isZero()
    }

    private fun publicMembers(file: File, owner: String): Set<String> {
        val members = mutableSetOf<String>()
        JarFile(file).use { jar ->
            ClassReader(jar.getInputStream(jar.getJarEntry("$owner.class"))).accept(
                object : ClassVisitor(Opcodes.ASM9) {
                    override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                        if (access and Opcodes.ACC_PUBLIC != 0) members.add("field ${access and Opcodes.ACC_STATIC} $name $descriptor")
                        return null
                    }
                    override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                        if (access and Opcodes.ACC_PUBLIC != 0) members.add("method ${access and Opcodes.ACC_STATIC} $name $descriptor")
                        return null
                    }
                },
                ClassReader.SKIP_CODE
            )
        }
        return members
    }

    companion object {
        private const val ENTRY = "io/github/gradlenexus/publishplugin/NexusPublishPlugin"
        private const val PLUGIN_ID = "io.github.gradle-nexus.publish-plugin"
    }
}
