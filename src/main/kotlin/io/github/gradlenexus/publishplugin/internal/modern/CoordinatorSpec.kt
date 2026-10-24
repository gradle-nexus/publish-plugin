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

import io.github.gradlenexus.publishplugin.settings.NexusPublishSettings
import io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings
import java.io.Serializable
import java.time.Duration
import java.util.ArrayList
import java.util.Collections

/**
 * Immutable root-only channel, distinct from the universal bootstrap.
 */
class CoordinatorSpec(settings: NexusPublishSettings) : Serializable {
    @JvmField
    val useStaging: Boolean? = settings.useStaging.getOrNull()

    @JvmField
    val packageGroup: String? = settings.packageGroup.getOrNull()

    @JvmField
    val repositoryDescription: String? = settings.repositoryDescription.getOrNull()

    @JvmField
    val clientTimeout: Duration = settings.clientTimeout.get()

    @JvmField
    val connectTimeout: Duration = settings.connectTimeout.get()

    @JvmField
    val delayBetween: Duration = settings.transitionCheckOptions.delayBetween.get()

    @JvmField
    val maxRetries: Int = settings.transitionCheckOptions.maxRetries.get()

    @JvmField
    val repositories: List<Repository>

    init {
        val values: MutableList<Repository> = ArrayList<Repository>()
        for (repository in settings.repositories) values.add(Repository(repository))
        repositories = Collections.unmodifiableList(values)
    }

    class Repository internal constructor(repository: NexusRepositorySettings) : Serializable {
        @JvmField
        val name: String = repository.getName()

        @JvmField
        val nexusUrl: String? = if (repository.nexusUrl.isPresent) repository.nexusUrl.get().toString() else null

        @JvmField
        val snapshotRepositoryUrl: String? =
            if (repository.snapshotRepositoryUrl.isPresent) repository.snapshotRepositoryUrl.get().toString() else null

        @JvmField
        val username: String? = repository.username.getOrNull()

        @JvmField
        val password: String? = repository.password.getOrNull()

        @JvmField
        val stagingProfileId: String? = repository.stagingProfileId.getOrNull()

        @JvmField
        val stagingRepositoryId: String? = repository.stagingRepositoryId.getOrNull()

        @JvmField
        val publicationType: NexusRepositorySettings.PublicationType = repository.publicationType.get()

        @JvmField
        val allowInsecureProtocol: Boolean = repository.allowInsecureProtocol.get()

        @JvmField
        val explicitIvyLayout: Boolean = repository.isExplicitIvyLayout()

        @JvmField
        val m2Compatible: Boolean = repository.isM2Compatible()

        @JvmField
        val artifactPatterns: List<String> = Collections.unmodifiableList<String>(ArrayList<String>(repository.artifactPatterns))

        @JvmField
        val ivyPatterns: List<String> = Collections.unmodifiableList<String>(ArrayList<String>(repository.ivyPatterns))
    }
}
