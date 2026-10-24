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

import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.repositories.resolver.IvyResourcePattern
import org.gradle.api.internal.artifacts.repositories.resolver.M2ResourcePattern
import org.gradle.api.internal.artifacts.repositories.resolver.ResourcePattern
import org.gradle.internal.component.external.model.DefaultModuleComponentArtifactMetadata
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier
import org.gradle.internal.component.model.DefaultIvyArtifactName
import java.net.URI

/** Settings execution-only native path adapter. The fixed base is never contacted. */
internal object NativeIvyPaths : IvyPublicationPlan.Paths {
    private val base = URI.create("https://nexus-planning.invalid/fixed/")

    override fun destination(publication: PublicationDescription, identity: ArtifactIdentity, pattern: String, m2Compatible: Boolean): String {
        // Preserve the conservative path guard before URI construction can normalize traversal.
        require(pattern.isNotEmpty() && !pattern.startsWith('/') && pattern.none { it in "\\:%?#" }) { "NEXUS_INVALID_DESTINATION" }
        require(pattern.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "NEXUS_INVALID_DESTINATION" }
        listOf(publication.group, publication.module, publication.version, identity.name, identity.type, identity.extension, identity.classifier).forEach { value ->
            require(value.none { it in "\\:%?#[]" } && value.split('/').none { it == "." || it == ".." }) { "NEXUS_INVALID_DESTINATION" }
        }
        val coordinates = DefaultModuleComponentIdentifier.newId(DefaultModuleIdentifier.newId(publication.group, publication.module), publication.version)
        val artifact = DefaultModuleComponentArtifactMetadata(coordinates, DefaultIvyArtifactName(identity.name, identity.type, identity.extension, identity.classifier.ifEmpty { null }))
        val resource: ResourcePattern =
            if (m2Compatible) M2ResourcePattern(base, pattern) else IvyResourcePattern(base, pattern)
        val location = resource.getLocation(artifact).uri
        require(!location.isOpaque && location.scheme == base.scheme && location.rawAuthority == base.rawAuthority && location.rawUserInfo == null && location.rawQuery == null && location.rawFragment == null && location.normalize() == location) { "NEXUS_INVALID_DESTINATION" }
        require(location.rawPath.startsWith(base.rawPath) && location.path.startsWith(base.path)) { "NEXUS_INVALID_DESTINATION" }
        val relative = base.relativize(location)
        require(!relative.isAbsolute && relative.rawAuthority == null && relative.rawQuery == null && relative.rawFragment == null) { "NEXUS_INVALID_DESTINATION" }
        return relative.path.also { IvyPublicationPlan.canonical(it) }
    }
}
