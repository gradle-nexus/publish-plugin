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

package io.github.gradlenexus.publishplugin.internal.modern.transport

import org.gradle.api.artifacts.repositories.AuthenticationSupported
import org.gradle.api.internal.artifacts.BaseRepositoryFactory
import org.gradle.api.internal.artifacts.ImmutableModuleIdentifierFactory
import org.gradle.api.internal.artifacts.repositories.metadata.IvyMutableModuleMetadataFactory
import org.gradle.api.internal.tasks.TaskDependencyFactory
import org.gradle.api.model.ObjectFactory
import org.gradle.api.publish.ivy.IvyArtifact
import org.gradle.api.publish.ivy.internal.artifact.FileBasedIvyArtifact
import org.gradle.api.publish.ivy.internal.publisher.IvyNormalizedPublication
import org.gradle.api.publish.ivy.internal.publisher.IvyPublicationCoordinates
import org.gradle.api.publish.ivy.internal.publisher.IvyPublisher
import org.gradle.api.publish.ivy.internal.publisher.ValidatingIvyPublisher
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.internal.artifact.FileBasedMavenArtifact
import org.gradle.api.publish.maven.internal.publisher.MavenNormalizedPublication
import org.gradle.api.publish.maven.internal.publisher.MavenPublicationCoordinates
import org.gradle.api.publish.maven.internal.publisher.MavenPublishers
import org.gradle.api.publish.maven.internal.publisher.ValidatingMavenPublisher
import org.gradle.authentication.http.BasicAuthentication
import org.gradle.internal.resource.local.FileResourceRepository
import org.gradle.internal.service.ServiceRegistry
import java.io.File
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Execution-local adapter to the consuming Gradle's publishing implementation.
 * Never retain this adapter, its services or normalized models in task state.
 * Internal API compatibility is qualified by packaged consumer tests.
 * Upload timeouts/retries/redirects follow Gradle's transport policy, as in legacy
 * mode. Plugin timeout settings configure Nexus staging REST calls separately.
 */
internal class GradlePublicationTransport(private val services: ServiceRegistry) {
    private val objects = services.get(ObjectFactory::class.java)
    private val repositories = services.get(BaseRepositoryFactory::class.java)
    private val dependencies = services.get(TaskDependencyFactory::class.java)

    /** Validate the complete action-local plan without a session or remote I/O. */
    fun validate(plan: MavenPublicationPlan.Plan) =
        validateMaven(maven(plan))

    fun validate(plan: IvyPublicationPlan.Plan) =
        validateIvy(ivy(plan.publications))

    private fun validateMaven(publications: List<MavenNormalizedPublication>) {
        val validator = ValidatingMavenPublisher { _, _ -> }
        val repository = repositories.createMavenRepository()
        publications.forEach { validator.publish(it, repository) }
    }

    private fun validateIvy(publications: List<IvyNormalizedPublication>) {
        val validator = ValidatingIvyPublisher(
            { _, _ -> },
            services.get(ImmutableModuleIdentifierFactory::class.java),
            services.get(FileResourceRepository::class.java),
            services.get(IvyMutableModuleMetadataFactory::class.java)
        )
        val repository = repositories.createIvyRepository()
        publications.forEach { validator.publish(it, repository) }
    }

    fun publish(directories: List<Path>, format: String, target: DeploymentEndpoint, username: String?, password: String?, temporaryDirectory: File, artifactPatterns: List<String>, ivyPatterns: List<String>, m2Compatible: Boolean) {
        require((username == null) == (password == null)) { "NEXUS_INCOMPLETE_CREDENTIALS" }
        // Fresh action-local inputs; validate the complete normalized union before the first PUT.
        if (format == "maven") {
            val publications = maven(MavenPublicationPlan.plan(directories))
            validateMaven(publications)
            val repository = repositories.createMavenRepository()
            repository.name = "nexusAggregateMaven"
            repository.url = target.getUri()
            repository.isAllowInsecureProtocol = target.getUri().scheme.equals("http", ignoreCase = true)
            credentials(repository, username, password)
            val publisher = ValidatingMavenPublisher(
                services.get(MavenPublishers::class.java).getRemotePublisher { temporaryDirectory.apply { mkdirs() } }
            )
            publications.forEach { publisher.publish(it, repository) }
        } else {
            require(format == "ivy") { "NEXUS_UNSUPPORTED_PUBLICATION_FORMAT" }
            val publications = ivy(PublicationDescription.read(directories, "ivy"))
            validateIvy(publications)
            val repository = repositories.createIvyRepository()
            repository.name = "nexusAggregateIvy"
            repository.url = target.getUri()
            repository.isAllowInsecureProtocol = target.getUri().scheme.equals("http", ignoreCase = true)
            repository.patternLayout { layout ->
                layout.setM2compatible(m2Compatible)
                artifactPatterns.forEach { layout.artifact(it) }
                ivyPatterns.forEach { layout.ivy(it) }
            }
            credentials(repository, username, password)
            val publisher = services.get(IvyPublisher::class.java)
            publications.forEach { publisher.publish(it, repository) }
        }
    }

    private fun credentials(repository: AuthenticationSupported, username: String?, password: String?) {
        if (username != null) {
            repository.credentials { credentials ->
                credentials.username = username
                credentials.password = password
            }
            repository.authentication { it.create("basic", BasicAuthentication::class.java) }
        }
    }

    private fun maven(plan: MavenPublicationPlan.Plan): List<MavenNormalizedPublication> =
        plan.publications.map { publication ->
            val coordinates = objects.newInstance(MavenPublicationCoordinates::class.java)
            coordinates.groupId.set(publication.group)
            coordinates.artifactId.set(publication.module)
            coordinates.version.set(publication.version)
            val artifacts = linkedSetOf<MavenArtifact>()
            var pom: MavenArtifact? = null
            publication.artifacts.filter { it.role != "checksum" }.forEach { entry ->
                val identity = requireNotNull(entry.identity)
                val artifact = FileBasedMavenArtifact(payload(entry), dependencies)
                artifact.extension = identity.extension
                artifact.classifier = identity.classifier.ifEmpty { null }
                artifacts.add(artifact)
                if (entry.role == "pom") {
                    require(pom == null) { "NEXUS_DUPLICATE_POM" }
                    pom = artifact
                }
            }
            val descriptor = requireNotNull(pom) { "NEXUS_MISSING_POM" }
            MavenNormalizedPublication(publication.name, coordinates, packaging(descriptor.file), descriptor, null, artifacts)
        }

    private fun ivy(publications: List<PublicationDescription>): List<IvyNormalizedPublication> =
        publications.map { publication ->
            val coordinates = objects.newInstance(IvyPublicationCoordinates::class.java)
            coordinates.organisation.set(publication.group)
            coordinates.module.set(publication.module)
            coordinates.revision.set(publication.version)
            val artifacts = linkedSetOf<IvyArtifact>()
            var descriptor: File? = null
            publication.artifacts.filter { it.role != "checksum" }.forEach { entry ->
                val identity = requireNotNull(entry.identity)
                val file = payload(entry)
                val artifact = FileBasedIvyArtifact(file, coordinates, dependencies)
                artifact.name = identity.name
                artifact.type = identity.type
                artifact.extension = identity.extension
                artifact.classifier = identity.classifier.ifEmpty { null }
                artifact.conf = identity.conf
                artifacts.add(artifact)
                if (entry.role == "ivy-descriptor") {
                    require(descriptor == null) { "NEXUS_DUPLICATE_IVY_DESCRIPTOR" }
                    descriptor = file
                }
            }
            val identity = services.get(ImmutableModuleIdentifierFactory::class.java).moduleWithVersion(coordinates.organisation.get(), coordinates.module.get(), coordinates.revision.get())
            IvyNormalizedPublication(publication.name, identity, requireNotNull(descriptor) { "NEXUS_MISSING_IVY_DESCRIPTOR" }, artifacts)
        }

    private fun payload(entry: PublicationArtifact): File {
        entry.payload.verify()
        return entry.payload.getFile().toFile()
    }

    private fun packaging(pom: File): String {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "")
        factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "")
        val document = factory.newDocumentBuilder().parse(pom)
        val elements = document.documentElement.childNodes
        return (0 until elements.length).map { elements.item(it) }
            .firstOrNull { it.nodeName == "packaging" }?.textContent?.trim() ?: "jar"
    }
}
