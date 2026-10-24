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

package io.github.gradlenexus.publishplugin.internal.modern

import io.github.gradlenexus.publishplugin.internal.modern.bundle.PrepareBundle
import io.github.gradlenexus.publishplugin.internal.modern.bundle.ProducerBundles
import io.github.gradlenexus.publishplugin.internal.modern.bundle.ResetBundle
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.ivy.IvyPublication
import org.gradle.api.publish.ivy.tasks.PublishToIvyRepository
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

/** Project model wiring only; automatic Settings installation and task execution stay in TestKit. */
class ProducerModelTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `empty producer exposes both format variants with preparation dependencies and no workflow dependency`() {
        val root = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val project = ProjectBuilder.builder().withName("child").withParent(root).build()
        val bootstrap = BootstrapSpec(listOf(":", ":child"), emptySet(), false)

        ProducerBundles.apply(project, bootstrap)

        listOf("maven", "ivy").forEach { format ->
            val capital = BootstrapSpec.capitalize(format)
            val variant = project.configurations.getByName("nexus${capital}BundleElements")
            val prepare = project.tasks.getByName("prepareNexus${capital}Bundle") as PrepareBundle
            val reset = project.tasks.getByName("resetNexus${capital}Bundle") as ResetBundle
            assertThat(variant.isCanBeConsumed).isTrue()
            assertThat(variant.isCanBeResolved).isFalse()
            assertThat(variant.attributes.getAttribute(ProducerBundles.FORMAT)).isEqualTo(format)
            assertThat(variant.attributes.getAttribute(ProducerBundles.SCHEMA)).isEqualTo(BootstrapSpec.BUNDLE_SCHEMA)
            assertThat(variant.outgoing.artifacts.single().buildDependencies.getDependencies(null)).containsExactlyInAnyOrder(reset, prepare)
            assertThat(prepare.taskDependencies.getDependencies(prepare)).containsExactly(reset)
            assertThat(reset.taskDependencies.getDependencies(reset)).isEmpty()
            assertThat(prepare.producerPath.get()).isEqualTo(":child")
            assertThat(prepare.format.get()).isEqualTo(format)
            assertThat(prepare.publications.get()).isEmpty()
            assertThat(variant.outgoing.artifacts.single().file).isEqualTo(project.layout.buildDirectory.dir("nexus-publishing/bundles/$format").get().asFile)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `native publication additions and removals update only their format preparation`(publishingFirst: Boolean) {
        val project = ProjectBuilder.builder().withProjectDir(directory.toFile()).build()
        val bootstrap = BootstrapSpec(listOf(":"), emptySet(), false)
        if (publishingFirst) {
            project.pluginManager.apply("maven-publish")
            project.pluginManager.apply("ivy-publish")
        }
        ProducerBundles.apply(project, bootstrap)
        if (!publishingFirst) {
            project.pluginManager.apply("maven-publish")
            project.pluginManager.apply("ivy-publish")
        }
        val publications = project.extensions.getByType(PublishingExtension::class.java).publications
        val maven = publications.create("main", MavenPublication::class.java)
        val ivy = publications.create("other", IvyPublication::class.java)
        val mavenExport = project.tasks.getByName("exportNexusMavenMain") as PublishToMavenRepository
        val ivyExport = project.tasks.getByName("exportNexusIvyOther") as PublishToIvyRepository
        val mavenPrepare = project.tasks.getByName("prepareNexusMavenBundle") as PrepareBundle
        val ivyPrepare = project.tasks.getByName("prepareNexusIvyBundle") as PrepareBundle

        assertThat(mavenExport.publication).isSameAs(maven)
        assertThat(ivyExport.publication).isSameAs(ivy)
        assertThat(mavenPrepare.taskDependencies.getDependencies(mavenPrepare).map { it.name })
            .containsExactlyInAnyOrder("resetNexusMavenBundle", "exportNexusMavenMain")
        assertThat(ivyPrepare.taskDependencies.getDependencies(ivyPrepare).map { it.name })
            .containsExactlyInAnyOrder("resetNexusIvyBundle", "exportNexusIvyOther")
        assertThat(mavenPrepare.publications.get().keys).containsExactly("main")
        assertThat(ivyPrepare.publications.get().keys).containsExactly("other")
        assertThat(project.repositories).isEmpty()

        publications.remove(maven)
        assertThat(mavenPrepare.publications.get()).isEmpty()
        assertThat(mavenPrepare.taskDependencies.getDependencies(mavenPrepare).map { it.name }).containsExactly("resetNexusMavenBundle")
        assertThat(ivyPrepare.publications.get().keys).containsExactly("other")
        publications.remove(ivy)
        assertThat(ivyPrepare.publications.get()).isEmpty()
        assertThat(ivyPrepare.taskDependencies.getDependencies(ivyPrepare).map { it.name }).containsExactly("resetNexusIvyBundle")
    }
}
