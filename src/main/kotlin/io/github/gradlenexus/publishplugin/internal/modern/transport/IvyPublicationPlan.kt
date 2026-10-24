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

import java.io.IOException
import java.nio.file.Path
import java.util.Collections

/** Pure union/relationship validation; the consuming Gradle supplies path semantics at execution. */
internal object IvyPublicationPlan {
    interface Paths {
        fun destination(publication: PublicationDescription, identity: ArtifactIdentity, pattern: String, m2Compatible: Boolean): String
    }

    fun plan(directories: List<Path>, artifactPatterns: List<String>, ivyPatterns: List<String>, m2Compatible: Boolean, nativeCustomLayout: Boolean, paths: Paths): Plan =
        planPublications(PublicationDescription.read(directories, "ivy"), artifactPatterns, ivyPatterns, m2Compatible, nativeCustomLayout, paths)

    fun planPublications(publications: List<PublicationDescription>, artifactPatterns: List<String>, ivyPatterns: List<String>, m2Compatible: Boolean, nativeCustomLayout: Boolean, paths: Paths): Plan {
        val result = LinkedHashMap<String, PayloadFile>()
        publications.forEach { publication ->
            val entries = publication.artifacts.associateBy { it.destination }
            if (nativeCustomLayout) require(publication.artifacts.none { it.role == "gradle-module" }) { "NEXUS_IVY_METADATA_POLICY_MISMATCH_BEFORE_DEPLOY" }
            fun destination(entry: PublicationArtifact, visiting: MutableSet<String>): String {
                require(visiting.add(entry.destination)) { "NEXUS_RELATIONSHIP_CYCLE" }
                if (entry.role == "checksum") {
                    val related = requireNotNull(entry.relatedTo) { "NEXUS_INVALID_CHECKSUM_RELATIONSHIP" }
                    val target = entries[related]
                    require(target != null && entry.destination.startsWith("$related.")) { "NEXUS_INVALID_CHECKSUM_RELATIONSHIP" }
                    val suffix = entry.destination.substring(related.length)
                    require(suffix in listOf(".md5", ".sha1", ".sha256", ".sha512")) { "NEXUS_INVALID_CHECKSUM_RELATIONSHIP" }
                    return destination(target, visiting) + suffix
                }
                val identity = requireNotNull(entry.identity)
                val patterns = if (identity.type == "ivy" && ivyPatterns.isNotEmpty()) ivyPatterns else artifactPatterns
                require(patterns.isNotEmpty()) { "NEXUS_MISSING_IVY_PATTERN" }
                // Native publishing chooses the first pattern, including typed ordinary ivy artifacts.
                return paths.destination(publication, identity, patterns.first(), m2Compatible)
            }
            publication.artifacts.forEach { entry ->
                val path = destination(entry, HashSet())
                canonical(path)
                require(result.putIfAbsent(path, entry.payload) == null) { "NEXUS_DESTINATION_COLLISION" }
            }
        }
        return Plan(publications, result).also { it.verify() }
    }

    fun canonical(path: String) {
        require(!(path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains(":") || path.contains("%") || path.contains("[") || path.contains("]") || path.contains("?") || path.contains("#"))) { "NEXUS_INVALID_DESTINATION" }
        for (part in path.split('/')) require(!(part.isEmpty() || part == "." || part == "..")) { "NEXUS_INVALID_DESTINATION" }
    }

    class Plan internal constructor(publications: List<PublicationDescription>, payloads: Map<String, PayloadFile>) {
        val publications: List<PublicationDescription> = Collections.unmodifiableList(ArrayList(publications))
        private val payloads: Map<String, PayloadFile> = Collections.unmodifiableMap(LinkedHashMap(payloads))
        fun getPayloads(): Map<String, PayloadFile> =
            payloads

        @Throws(IOException::class)
        fun verify() {
            payloads.values.forEach { it.verify() }
        }
    }
}
