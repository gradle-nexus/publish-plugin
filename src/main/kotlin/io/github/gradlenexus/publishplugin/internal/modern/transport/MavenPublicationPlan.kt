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

/** Complete immutable Maven union; no repository system or protocol imports. */
internal object MavenPublicationPlan {
    fun plan(directories: List<Path>): Plan =
        planPublications(PublicationDescription.read(directories, "maven"))

    fun planPublications(publications: List<PublicationDescription>): Plan {
        val destinations = HashSet<String>()
        publications.forEach { publication ->
            publication.artifacts.forEach { entry ->
                require(destinations.add(entry.destination)) { "NEXUS_DESTINATION_COLLISION" }
                if (entry.role != "checksum") {
                    val identity = requireNotNull(entry.identity)
                    require(entry.role != "artifact" || identity.name == publication.module) { "NEXUS_MAVEN_IDENTITY_MAPPING" }
                    val expected = publication.group.replace('.', '/') + "/" + publication.module + "/" + publication.version + "/" + publication.module + "-" + publication.version +
                        (if (identity.classifier.isEmpty()) "" else "-" + identity.classifier) + "." + identity.extension
                    require(entry.destination == expected) { "NEXUS_MAVEN_IDENTITY_MAPPING" }
                }
            }
        }
        return Plan(publications).also { it.verify() }
    }
    class Plan internal constructor(publications: List<PublicationDescription>) {
        val publications: List<PublicationDescription> = Collections.unmodifiableList(ArrayList(publications))

        fun isEmpty(): Boolean =
            publications.all { publication -> publication.artifacts.all { it.role == "checksum" } }

        @Throws(IOException::class)
        fun verify() {
            publications.forEach { publication ->
                publication.artifacts.forEach { entry ->
                    if (entry.role != "checksum") entry.payload.verify()
                }
            }
        }
    }
}
