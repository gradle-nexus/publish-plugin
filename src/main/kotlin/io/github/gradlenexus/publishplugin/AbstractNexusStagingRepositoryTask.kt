/*
 * Copyright 2019 the original author or authors.
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

package io.github.gradlenexus.publishplugin

import io.github.gradlenexus.publishplugin.internal.NexusClient
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.kotlin.dsl.property
import java.net.URI
import java.time.Duration
import javax.inject.Inject

@Suppress("UnstableApiUsage")
abstract class AbstractNexusStagingRepositoryTask @Inject
constructor(objects: ObjectFactory, extension: NexusPublishExtension, repository: NexusRepository) : DefaultTask() {

    @get:Input
    protected val serverUrl: Property<URI> = objects.property()

    @get:Optional
    @get:Input
    protected val username: Property<String> = objects.property()

    @get:Optional
    @get:Input
    protected val password: Property<String> = objects.property()

    @get:Optional
    @get:Input
    protected val packageGroup: Property<String> = objects.property()

    @get:Optional
    @get:Input
    protected val stagingProfileId: Property<String> = objects.property()

    @get:Input
    protected val repositoryName: Property<String> = objects.property()

    @get:Internal
    protected val clientTimeout: Property<Duration> = objects.property()

    @get:Internal
    protected val connectTimeout: Property<Duration> = objects.property()

    init {
        serverUrl.set(repository.nexusUrl)
        username.set(repository.username)
        password.set(repository.password)
        packageGroup.set(extension.packageGroup)
        stagingProfileId.set(repository.stagingProfileId)
        repositoryName.set(repository.name)
        clientTimeout.set(extension.clientTimeout)
        connectTimeout.set(extension.connectTimeout)
        this.onlyIf { extension.useStaging.getOrElse(false) }
    }

    protected fun determineStagingProfileId(client: NexusClient): String {
        var stagingProfileId = stagingProfileId.orNull
        if (stagingProfileId == null) {
            val packageGroup = packageGroup.get()
            logger.debug("No stagingProfileId set, querying for packageGroup '{}'", packageGroup)
            stagingProfileId = client.findStagingProfileId(packageGroup)
                    ?: throw GradleException("Failed to find staging profile for package group: $packageGroup")
        }
        return stagingProfileId
    }
}
