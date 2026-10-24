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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/** Native-dependent planning always runs in its owning Gradle, returning detached test evidence. */
internal object NativeIvyPlanEvidence {
    data class Payload(val file: File, val directory: Path, val relativePath: String, val size: Long, val sha256: String)

    @Synchronized
    fun plan(directories: List<Path>, artifacts: List<String>, descriptors: List<String>, m2: Boolean, custom: Boolean, runtime: ModernRuntime): Map<String, Payload> {
        val specification = mapOf("directories" to directories.map { it.toAbsolutePath().normalize().toString() }, "artifacts" to artifacts, "descriptors" to descriptors, "m2" to m2, "custom" to custom)
        val contents = directories.map { inventory(it) }
        val identity = Gson().toJson(listOf(specification, runtime.version, digest(ModernTestPaths.candidateJar), contents))
        generations[identity]?.let { return it }
        val project = File(ModernTestPaths.workspaceRoot, "native-ivy-plan-evidence/${UUID.randomUUID()}/consumer").apply { mkdirs() }
        val snapshots = directories.mapIndexed { index, root ->
            val snapshot = File(project, "bundles/$index")
            check(root.toFile().copyRecursively(snapshot))
            snapshot.toPath()
        }
        check(snapshots.map { inventory(it) } == contents) { "Bundle changed while capturing native evidence" }
        File(project, "settings.gradle").writeText("rootProject.name = 'native-plan-evidence'\n")
        File(project, "plan-input.json").writeText(Gson().toJson(specification + ("directories" to snapshots.map { it.toAbsolutePath().normalize().toString() })))
        File(project, "build.gradle").writeText(
            """
            import groovy.json.JsonSlurper
            import groovy.json.JsonOutput
            import java.nio.file.Paths
            buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
            abstract class PlanEvidence extends DefaultTask {
                @InputFile abstract RegularFileProperty getSpecification()
                @InputFiles abstract ConfigurableFileCollection getBundles()
                @OutputFile abstract RegularFileProperty getEvidence()
                @TaskAction void calculate() {
                    def input = new JsonSlurper().parse(specification.get().asFile)
                    def roots = input.directories.collect { Paths.get(it) }
                    def record
                    try {
                        def plan = io.github.gradlenexus.publishplugin.internal.modern.transport.IvyPublicationPlan.INSTANCE.plan(roots, input.artifacts, input.descriptors, input.m2, input.custom, io.github.gradlenexus.publishplugin.internal.modern.transport.NativeIvyPaths.INSTANCE)
                        plan.verify()
                        [plan.publications, plan.payloads, *plan.publications.collect { it.artifacts }].each { collection ->
                            try { collection.clear(); throw new AssertionError('mutable detached plan') }
                            catch (UnsupportedOperationException expected) { }
                        }
                        plan.payloads.values().each { payload ->
                            assert !payload.class.declaredFields.any { it.type == byte[].class || it.type.name.startsWith('org.gradle.') }
                        }
                        record = [payloads: plan.payloads.collectEntries { destination, payload ->
                            def file = payload.file
                            def directory = roots.collect { it.toRealPath() }.find { file.startsWith(it) }
                            assert directory != null
                            [(destination): [file: file.toString(), directory: directory.toString(), relativePath: directory.relativize(file).toString(), size: payload.size, sha256: payload.sha256]]
                        }]
                    } catch (Exception failure) {
                        record = [error: failure.message]
                    }
                    evidence.get().asFile.text = JsonOutput.toJson(record)
                }
            }
            tasks.register('planEvidence', PlanEvidence) {
                specification.set(layout.projectDirectory.file('plan-input.json'))
                bundles.from(layout.projectDirectory.dir('bundles'))
                evidence.set(layout.buildDirectory.file('plan-evidence.json'))
                outputs.upToDateWhen { false }
            }
            """.trimIndent() + "\n"
        )
        // Owning publisher and independent native controls already exercise strict store/reuse.
        ModernGradleBuild(project, false, runtime).run("planEvidence")
        val result = Gson().fromJson(File(project, "build/plan-evidence.json").readText(), Map::class.java)
        if (result.containsKey("error")) throw IllegalArgumentException(result["error"].toString())
        @Suppress("UNCHECKED_CAST")
        val payloads = (result["payloads"] as Map<String, Map<String, Any>>).mapValues { (_, value) ->
            Payload(File(value.getValue("file").toString()), File(value.getValue("directory").toString()).toPath(), value.getValue("relativePath").toString(), (value.getValue("size") as Number).toLong(), value.getValue("sha256").toString())
        }
        return Collections.unmodifiableMap(payloads).also { generations[identity] = it }
    }
    private val generations = mutableMapOf<String, Map<String, Payload>>()

    private fun inventory(root: Path): Map<String, String> =
        root.toFile().walkTopDown().onEnter { directory ->
            check(!Files.isSymbolicLink(directory.toPath())) { "Symbolic evidence directory" }
            true
        }.filter { it.isFile }.sortedBy { it.relativeTo(root.toFile()).path }.associate { file ->
            check(!Files.isSymbolicLink(file.toPath())) { "Symbolic evidence input" }
            file.relativeTo(root.toFile()).invariantSeparatorsPath to digest(file)
        }

    private fun digest(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
