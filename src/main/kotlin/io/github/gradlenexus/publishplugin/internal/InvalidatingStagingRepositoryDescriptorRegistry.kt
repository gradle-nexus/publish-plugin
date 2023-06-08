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

class InvalidatingStagingRepositoryDescriptorRegistry {

    private val mapping = ConcurrentHashMap<String, StagingRepositoryDescriptor>()
    private val invalidateMapping = ConcurrentHashMap<String, ArtifactRepository>()

    operator fun set(name: String, descriptor: StagingRepositoryDescriptor) {
        mapping[name] = descriptor
        invalidateMapping.remove(name)?.invalidate()
    }

    operator fun get(name: String) = mapping[name] ?: throw IllegalStateException("No staging repository with name $name created")

    fun invalidateLater(name: String, artifactRepository: ArtifactRepository) {
        invalidateMapping[name] = artifactRepository
    }

    fun tryGet(name: String) = mapping[name]

    override fun toString() = mapping.toString()

    companion object {
        private val log: Logger = LoggerFactory.getLogger(BasicActionRetrier::class.java)

        private fun ArtifactRepository.invalidate() {
            if (this is AbstractResolutionAwareArtifactRepository) {
                try {
                    val invalidateDescriptorMethod = AbstractResolutionAwareArtifactRepository::class.java.declaredMethods.find { it.name.contains("invalidateDescriptor") }
                    invalidateDescriptorMethod?.isAccessible = true
                    invalidateDescriptorMethod?.invoke(this)
                } catch (e: Exception) {
                    log.warn("Failed to invalidate artifacty repository URL, publishing will not work correctly")
                }
            }
        }
    }
}
