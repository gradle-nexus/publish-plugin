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

package io.github.gradlenexus.publishplugin.internal

import org.gradle.api.artifacts.repositories.ArtifactRepository
import org.gradle.api.internal.artifacts.repositories.AbstractResolutionAwareArtifactRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/*
 * This class is a temporary workaround to invalidate the repository description on a IvyArtifactRepository after the creation of the staging sonatype repo
 */
class InvalidatingStagingRepositoryDescriptorRegistry : StagingRepositoryDescriptorRegistry() {

    private val invalidateMapping = ConcurrentHashMap<String, ArtifactRepository>()

    override operator fun set(name: String, descriptor: StagingRepositoryDescriptor) {
        super.set(name, descriptor)
        invalidateMapping.remove(name)?.invalidate()
    }

    fun invalidateLater(name: String, artifactRepository: ArtifactRepository) {
        invalidateMapping[name] = artifactRepository
    }

    companion object {
        private val log: Logger = LoggerFactory.getLogger(InvalidatingStagingRepositoryDescriptorRegistry::class.java)

        private fun ArtifactRepository.invalidate() {
            if (this is AbstractResolutionAwareArtifactRepository<*>) {
                try {
                    AbstractResolutionAwareArtifactRepository::class.java
                        .getDeclaredMethod("invalidateDescriptor")
                        .apply { isAccessible = true }
                        .invoke(this)
                } catch (e: Exception) {
                    log.warn("Failed to invalidate artifact repository URL, publishing will not work correctly.")
                }
            }
        }
    }
}
