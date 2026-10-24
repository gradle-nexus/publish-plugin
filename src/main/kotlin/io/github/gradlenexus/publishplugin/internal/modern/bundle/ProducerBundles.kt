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

package io.github.gradlenexus.publishplugin.internal.modern.bundle

import io.github.gradlenexus.publishplugin.internal.modern.BootstrapSpec
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.publish.Publication
import org.gradle.api.publish.PublicationContainer
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.ivy.IvyPublication
import org.gradle.api.publish.ivy.tasks.PublishToIvyRepository
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.api.tasks.TaskProvider
import org.gradle.plugins.signing.Sign
import java.io.File
import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.TreeSet

/**
 * Local native file exporter; never a producer remote route or default publish dependency.
 */
object ProducerBundles {
    @JvmField
    val FORMAT: Attribute<String> = Attribute.of("io.github.gradle-nexus.publish-plugin.bundle-format", String::class.java)

    @JvmField
    val SCHEMA: Attribute<String> = Attribute.of("io.github.gradle-nexus.publish-plugin.bundle-schema", String::class.java)

    @JvmStatic
    fun apply(project: Project, bootstrap: BootstrapSpec) {
        for (format in listOf("maven", "ivy")) {
            val capital: String = BootstrapSpec.capitalize(format)
            val directory: Provider<Directory> = project.layout.buildDirectory.dir("nexus-publishing/bundles/$format")
            val state: Provider<Directory> = project.layout.buildDirectory.dir("nexus-publishing/state/$format")
            val reset: TaskProvider<ResetBundle> = project.tasks.register("resetNexus" + capital + "Bundle", ResetBundle::class.java) { task ->
                task.mustRunAfter(bootstrap.getWorkflowResetPaths())
                task.directory.set(directory)
                task.stateDirectory.set(state)
            }
            val prepare: TaskProvider<PrepareBundle> = project.tasks.register("prepareNexus" + capital + "Bundle", PrepareBundle::class.java) { task ->
                task.dependsOn(reset)
                task.mustRunAfter(bootstrap.getWorkflowResetPaths())
                task.directory.set(directory)
                task.stateDirectory.set(state)
                task.manifest.set(directory.map { value -> value.file("manifest.json") })
                task.producerPath.set(project.path)
                task.format.set(format)
                task.publications.convention(Collections.emptyMap())
            }
            val variant: Configuration = project.configurations.create("nexus" + capital + "BundleElements")
            variant.isCanBeConsumed = true
            variant.isCanBeResolved = false
            variant.attributes.attribute(FORMAT, format).attribute(SCHEMA, BootstrapSpec.BUNDLE_SCHEMA)
            variant.outgoing.artifact(directory) { artifact -> artifact.builtBy(reset, prepare) }
            val exports: MutableMap<String, TaskProvider<out Task>> = LinkedHashMap<String, TaskProvider<out Task>>()
            project.plugins.withType(PublishingPlugin::class.java) {
                val publications: PublicationContainer =
                    project.extensions.getByType(PublishingExtension::class.java).publications
                publications.all { publication ->
                    if (!(if (format == "maven") publication is MavenPublication else publication is IvyPublication)) return@all
                    val name: String = publication.name
                    if (publication is IvyPublication && bootstrap.isSuppressIvyModuleMetadata()) {
                        val metadataTaskName: String =
                            "generateMetadataFileFor" + BootstrapSpec.capitalize(name) + "Publication"
                        project.tasks.withType(GenerateModuleMetadata::class.java).configureEach { task ->
                            if (task.name.equals(metadataTaskName)) task.isEnabled = false
                        }
                    }
                    val repositoryDirectory: Provider<Directory> = directory.map { value ->
                        value.dir(
                            "payload/" + BundleCodec.namespace(
                                name
                            )
                        )
                    }
                    val export: TaskProvider<out Task>
                    if (publication is MavenPublication) {
                        val repository: MavenArtifactRepository = project.repositories.maven { repo ->
                            repo.name = "nexusPrivateMaven$name"
                            repo.setUrl(repositoryDirectory)
                        }
                        project.repositories.remove(repository)
                        export = project.tasks.register(
                            NativeTaskNames.exportName("maven", name),
                            PublishToMavenRepository::class.java
                        ) { task ->
                            task.mustRunAfter(bootstrap.getWorkflowResetPaths())
                            task.publication = publication
                            task.setRepository(repository)
                            task.onlyIf(
                                NativeDestinationGate(
                                    project.provider { task.repository.url },
                                    repositoryDirectory.get().asFile.toURI()
                                )
                            )
                            task.dependsOn(reset)
                            task.outputs.dir(repositoryDirectory)
                            task.outputs.upToDateWhen { false }
                            task.outputs.doNotCacheIf("Native export success must be current") { true }
                            task.outputs.file(state.map { value -> value.file(BundleCodec.namespace(name) + ".ok") })
                            task.doLast(ExportSuccess(state.get().file(BundleCodec.namespace(name) + ".ok").asFile))
                        }
                    } else {
                        val repository: IvyArtifactRepository = project.repositories.ivy { repo ->
                            repo.name = "nexusPrivateIvy$name"
                            repo.setUrl(repositoryDirectory)
                            repo.patternLayout { layout ->
                                val pattern =
                                    "[organisation]/[module]/[revision]/[type]/[artifact]-[revision](-[classifier])(.[ext])"
                                layout.artifact(pattern)
                                layout.ivy(pattern)
                                layout.setM2compatible(true)
                            }
                        }
                        project.repositories.remove(repository)
                        export = project.tasks.register(
                            NativeTaskNames.exportName("ivy", name),
                            PublishToIvyRepository::class.java
                        ) { task ->
                            task.mustRunAfter(bootstrap.getWorkflowResetPaths())
                            task.publication = publication as IvyPublication
                            task.setRepository(repository)
                            task.onlyIf(
                                NativeDestinationGate(
                                    project.provider { task.repository.url },
                                    repositoryDirectory.get().asFile.toURI()
                                )
                            )
                            task.dependsOn(reset)
                            task.outputs.dir(repositoryDirectory)
                            task.outputs.upToDateWhen { false }
                            task.outputs.doNotCacheIf("Native export success must be current") { true }
                            task.outputs.file(state.map { value -> value.file(BundleCodec.namespace(name) + ".ok") })
                            task.doLast(ExportSuccess(state.get().file(BundleCodec.namespace(name) + ".ok").asFile))
                        }
                    }
                    exports[name] = export
                    val snapshot: MutableList<TaskProvider<out Task>> =
                        ArrayList<TaskProvider<out Task>>(exports.values)
                    prepare.configure { task ->
                        task.exportedPayloads.setFrom(snapshot)
                        task.publications.put(
                            name,
                            project.provider {
                                specification(
                                    publication,
                                    project
                                )
                            }
                        )
                    }
                }
                publications.whenObjectRemoved { publication ->
                    if (exports.remove(publication.name) != null) {
                        val snapshot: MutableList<TaskProvider<out Task>> =
                            ArrayList<TaskProvider<out Task>>(exports.values)
                        prepare.configure { task ->
                            task.exportedPayloads.setFrom(snapshot)
                        }
                        // Rebuild only from current publication models at configuration time, detached by provider serialization.
                        prepare.configure { task ->
                            task.publications.set(Collections.emptyMap())
                            for (current in publications) {
                                if (exports.containsKey(current.name)) {
                                    task.publications.put(
                                        current.name,
                                        project.provider {
                                            specification(
                                                current,
                                                project
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @JvmStatic
    private fun specification(publication: Publication, project: Project): String {
        val artifacts: MutableList<MutableMap<String, String?>> = ArrayList<MutableMap<String, String?>>()
        val group: String
        val module: String
        val version: String
        if (publication is MavenPublication) {
            val value: MavenPublication = publication
            group = value.groupId
            module = value.artifactId
            version = value.version
            for (artifact in value.artifacts) artifacts.add(identity(module, artifact.classifier, artifact.extension))
        } else {
            val value: IvyPublication = publication as IvyPublication
            group = value.organisation
            module = value.module
            version = value.revision
            for (artifact in value.artifacts) {
                val item: MutableMap<String, String?> = identity(artifact.name, artifact.classifier, artifact.extension)
                item["type"] = artifact.type
                item["conf"] = artifact.conf
                artifacts.add(item)
            }
        }
        val signatureExtensions: MutableSet<String> = TreeSet<String>()
        // Public signing-plugin publication task convention; only this publication's Sign model is queried.
        val signingTask: Task? = project.tasks.findByName("sign" + BootstrapSpec.capitalize(publication.name) + "Publication")
        if (signingTask is Sign) {
            for (signature in signingTask.signatures) {
                val output: File? = signature.getFile()
                if (output != null && output.getName().contains(".")) signatureExtensions.add(output.getName().substring(output.getName().lastIndexOf('.') + 1))
            }
        }
        return BundleCodec.publication(publication.name, group, module, version, artifacts, signatureExtensions)
    }

    @JvmStatic
    private fun identity(name: String, classifier: String?, extension: String): MutableMap<String, String?> {
        val identity: MutableMap<String, String?> = TreeMap<String, String?>()
        identity["name"] = name
        identity["classifier"] = classifier ?: ""
        identity["extension"] = extension
        return identity
    }
}
