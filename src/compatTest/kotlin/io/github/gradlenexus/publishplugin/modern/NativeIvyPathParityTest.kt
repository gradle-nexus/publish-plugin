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

/** Independent file publishing establishes native path semantics before planner replacement. */
internal class NativeIvyPathParityTest {
    private val key by lazy { SoftwareSigningKey() }
    private val projects = mutableListOf<File>()

    @AfterEach
    fun removeSigningCaches() {
        projects.forEach { project ->
            val cache = File(project, ".gradle/configuration-cache")
            check(!cache.exists() || cache.deleteRecursively())
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `native resource patterns match independent native publishing paths`(runtime: ModernRuntime) {
        listOf("default", "custom", "fallback", "dotted-custom").forEach { mode ->
            val project = File(ModernTestPaths.workspaceRoot, "native-ivy-path-parity/${UUID.randomUUID()}").apply { mkdirs() }
            projects.add(project)
            File(project, "settings.gradle").writeText("rootProject.name = 'native'\n")
            File(project, "plain").writeText("custom artifact bytes\n")
            val typed = "[organisation]/[module]/[revision]/[type]/[artifact]-[revision](-[classifier])(.[ext])"
            val artifacts = if (mode == "default") listOf("[organisation]/[module]/[revision]/[artifact]-[revision](-[classifier])(.[ext])") else listOf(typed, "unused/[artifact].[ext]")
            val descriptors = when (mode) {
                "default" -> listOf("[organisation]/[module]/[revision]/ivy-[revision].xml")
                "fallback" -> emptyList()
                else -> listOf(typed, "unused-ivy/[artifact].[ext]")
            }
            val m2 = mode in listOf("default", "custom")
            val binary = mode == "fallback"
            val layout = if (mode == "default") "layout 'maven'" else "patternLayout { ${artifacts.joinToString(";") { "artifact '$it'" }};${descriptors.joinToString(";") { "ivy '$it'" }};m2compatible=$m2 }"
            File(project, "build.gradle").writeText(
                """
                import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
                import org.gradle.api.internal.artifacts.repositories.resolver.IvyResourcePattern
                import org.gradle.api.internal.artifacts.repositories.resolver.M2ResourcePattern
                import org.gradle.api.internal.artifacts.repositories.resolver.ResourcePattern
                import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
                import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
                import org.gradle.internal.component.model.DefaultIvyArtifactName
                import groovy.json.JsonOutput
                buildscript { dependencies { classpath files('${ModernTestPaths.candidateJar.invariantSeparatorsPath}') } }
                plugins { id 'java-library'; id 'ivy-publish'; id 'signing' }
                group = 'probe.tools'; version = '1.0'
                publishing.publications {
                    main(IvyPublication) {
                        from components.java; module = 'native'
                        configurations.maybeCreate('runtime')
                        artifact(file('plain')) { name = 'special'; type = 'special-type'; extension = 'jar'; classifier = 'linux.x86'; conf = 'runtime' }
                        artifact(file('plain')) { name = 'extensionless'; type = 'plain-type'; extension = ''; classifier = 'plain' }
                        artifact(file('plain')) { name = 'spaced ü'; type = 'custom-type'; extension = 'txt'; classifier = 'with space ñ' }
                        ${if (mode == "default") "" else "artifact(file('plain')) { name = 'declared'; type = 'ivy'; extension = 'module'; classifier = 'custom' }"}
                    }
                }
                ${if (mode != "default") "tasks.withType(GenerateModuleMetadata).configureEach { enabled = false }" else ""}
                signing {
                    ${if (binary) "signatureTypes.defaultType = 'sig'" else ""}
                    useInMemoryPgpKeys(providers.environmentVariable('BundleFixture_PGP_KEY').get(), providers.environmentVariable('BundleFixture_PGP_PASSWORD').get())
                    sign publishing.publications
                }
                publishing.repositories { ivy { name = 'control'; url = layout.buildDirectory.dir('control'); $layout } }
                abstract class ProbePaths extends DefaultTask {
                    @Input abstract ListProperty<Map<String, String>> getIdentities()
                    @Input abstract ListProperty<String> getArtifacts()
                    @Input abstract ListProperty<String> getDescriptors()
                    @Input abstract Property<Boolean> getM2()
                    @InputDirectory abstract DirectoryProperty getRepository()
                    @OutputFile abstract RegularFileProperty getEvidence()
                    @TaskAction void probe() {
                        def base = URI.create('https://nexus-planning.invalid/fixed/')
                        def coordinates = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId('probe.tools', 'native'), '1.0')
                        def escaped = []
                        def paths = identities.get().collect { identity ->
                            def choices = identity.type == 'ivy' && !descriptors.get().empty ? descriptors.get() : artifacts.get()
                            def artifact = new DefaultModuleComponentArtifactMetadata(coordinates, new DefaultIvyArtifactName(identity.name, identity.type, identity.extension, identity.classifier ?: null))
                            ResourcePattern pattern
                            if (m2.get()) { pattern = new M2ResourcePattern(base, choices.first()) } else { pattern = new IvyResourcePattern(base, choices.first()) }
                            def location = pattern.getLocation(artifact).uri
                            assert location.scheme == base.scheme && location.authority == base.authority
                            assert location.path.startsWith(base.path)
                            def description = new io.github.gradlenexus.publishplugin.internal.modern.transport.PublicationDescription('main', 'probe.tools', 'native', '1.0', [])
                            def detached = new io.github.gradlenexus.publishplugin.internal.modern.transport.ArtifactIdentity(identity.name, identity.type, identity.extension, identity.classifier, null)
                            def planned = io.github.gradlenexus.publishplugin.internal.modern.transport.NativeIvyPaths.INSTANCE.destination(description, detached, choices.first(), m2.get())
                            def relative = base.relativize(location)
                            if (relative.rawPath != relative.path) escaped.add([raw: relative.rawPath, decoded: relative.path])
                            assert planned == relative.path
                            planned
                        }
                        def actual = []
                        repository.get().asFile.eachFileRecurse { if (it.file) actual.add(repository.get().asFile.toPath().relativize(it.toPath()).toString().replace('\\', '/')) }
                        def payloads = actual.findAll { path -> paths.contains(path) || !['.md5', '.sha1', '.sha256', '.sha512'].any { suffix -> path.endsWith(suffix) } }
                        assert paths.toSet() == payloads.toSet(): "planned=" + paths + " actual=" + payloads
                        assert paths.size() == paths.toSet().size()
                        def description = new io.github.gradlenexus.publishplugin.internal.modern.transport.PublicationDescription('main', 'probe.tools', 'native', '1.0', [])
                        def detached = new io.github.gradlenexus.publishplugin.internal.modern.transport.ArtifactIdentity('native', 'jar', 'jar', '', null)
                        def invalid = ['../[artifact].[ext]', '/[artifact].[ext]', '//outside/[artifact]', 'https://outside/[artifact]', '%2e%2e/[artifact]', '[artifact].jar?query', '[artifact].jar#fragment', '[organisation]/../../[artifact]', '[organisation](/../)[artifact]', '[unknown]/[artifact]']
                        invalid.each { pattern ->
                            try {
                                io.github.gradlenexus.publishplugin.internal.modern.transport.NativeIvyPaths.INSTANCE.destination(description, detached, pattern, m2.get())
                                throw new AssertionError('accepted invalid pattern: ' + pattern)
                            } catch (IllegalArgumentException expected) { assert expected.message == 'NEXUS_INVALID_DESTINATION' }
                        }
                        def rejectedRoles = []
                        ['maven', 'ivy'].each { format ->
                            def directory = new File(evidence.get().asFile.parentFile, 'invalid-role-' + format)
                            def payload = new File(directory, 'payload/metadata.xml')
                            payload.parentFile.mkdirs(); payload.text = 'abc'
                            def publication = [name: 'invalidRole', group: 'probe.tools', module: 'native', version: '1.0', snapshot: false, kind: 'metadata-only', artifacts: [], localRepositoryMetadata: [], entries: [
                                [payload: 'payload/metadata.xml', destination: 'probe/tools/native/1.0/native-1.0.xml', role: format == 'maven' ? 'ivy-descriptor' : 'pom', size: 3, sha256: 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad', describes: []]
                            ]]
                            new File(directory, 'manifest.json').text = JsonOutput.toJson([schemaVersion: 1, producerPath: ':p', format: format, empty: false, publications: [publication]])
                            def diagnostic = 'NEXUS_UNSUPPORTED_' + format.toUpperCase() + '_ROLE'
                            def failure
                            try {
                                if (format == 'maven') {
                                    io.github.gradlenexus.publishplugin.internal.modern.transport.MavenPublicationPlan.INSTANCE.plan([directory.toPath()])
                                } else {
                                    io.github.gradlenexus.publishplugin.internal.modern.transport.IvyPublicationPlan.INSTANCE.plan([directory.toPath()], artifacts.get(), descriptors.get(), m2.get(), false, io.github.gradlenexus.publishplugin.internal.modern.transport.NativeIvyPaths.INSTANCE)
                                }
                            } catch (IllegalArgumentException expected) { failure = expected.message }
                            assert failure == diagnostic
                            rejectedRoles.add(diagnostic)
                        }
                        evidence.get().asFile.text = JsonOutput.toJson([paths: paths.sort(), identities: identities.get(), base: base.toString(), rejected: invalid, rejectedRoles: rejectedRoles, escaped: escaped])
                    }
                }
                tasks.register('probePaths', ProbePaths) {
                    dependsOn 'publishMainPublicationToControlRepository'
                    outputs.upToDateWhen { false }
                    artifacts.set(${artifacts.joinToString(",", "[", "]") { "'$it'" }})
                    descriptors.set(${descriptors.joinToString(",", "[", "]") { "'$it'" }})
                    m2.set($m2)
                    repository.set(layout.buildDirectory.dir('control'))
                    evidence.set(layout.buildDirectory.file('path-parity.json'))
                    identities.set(publishing.publications.main.publishableArtifacts.findAll { it.shouldBePublished() }.collect { [name: it.name, type: it.type, extension: it.extension, classifier: it.classifier ?: ''] })
                }
                """.trimIndent() + "\n"
            )
            ModernGradleBuild(project, false, runtime, mapOf("BundleFixture_PGP_KEY" to key.armored, "BundleFixture_PGP_PASSWORD" to key.password)).storeAndReuse("probePaths")
            val evidence = Gson().fromJson(File(project, "build/path-parity.json").readText(), Map::class.java)
            assertThat(evidence["paths"] as List<*>).isNotEmpty()
            assertThat(evidence["escaped"] as List<*>).isNotEmpty()
            assertThat((evidence["paths"] as List<String>).any { it.contains("spaced ü-1.0-with space ñ") }).isTrue()
            val identities = evidence["identities"] as List<Map<String, String>>
            if (mode != "default") assertThat(identities.any { it["name"] == "declared" && it["type"] == "ivy" }).isTrue()
            assertThat(identities.any { it["extension"] == "" }).isTrue()
            assertThat(identities.any { it["extension"]!!.endsWith(if (binary) ".sig" else ".asc") }).isTrue()
            println("NATIVE_IVY_PATH_PARITY runtime=${runtime.version} mode=$mode m2=$m2 identities=${identities.size} paths=${evidence["paths"]}")
        }
    }
}
