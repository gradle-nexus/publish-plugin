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

package io.github.gradlenexus.publishplugin.internal.modern.workflow

import io.github.gradlenexus.publishplugin.internal.modern.transport.DeploymentEndpoint
import io.github.gradlenexus.publishplugin.tasks.NexusStagingProfileDiagnostic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.provider.Property
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.Serializable

/**
 * Independent root-only diagnostic; it has no publication or session admission state.
 */
@DisableCachingByDefault(because = "A profile diagnostic queries the current server on every invocation")
abstract class StagingProfileDiagnostic :
    DefaultTask(),
    NexusStagingProfileDiagnostic {
    @get:Input abstract override val repositoryAlias: Property<String>

    @get:Input abstract override val packageGroup: Property<String>

    @get:Input abstract val useStaging: Property<Boolean>

    @get:Input abstract val insecure: Property<Boolean>

    @get:Input abstract val connectTimeout: Property<Int>

    @get:Input abstract val requestTimeout: Property<Int>

    @get:Internal abstract val endpoint: Property<String>

    @get:Internal abstract val username: Property<String>

    @get:Internal abstract val password: Property<String>

    init {
        onlyIf(StagingEnabled())
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Profile diagnostics must query current server state") { true }
    }

    @TaskAction
    @Throws(Exception::class)
    fun retrieve() {
        val value: String = endpoint.getOrNull() ?: throw GradleException("NEXUS_REPOSITORY_ENDPOINT_REQUIRED")
        val endpoint: DeploymentEndpoint = DeploymentEndpoint.validate(value, insecure.get())
        val group: String = packageGroup.get()
        val profile: String = Nexus2SessionBackend.retrieveProfile(endpoint.getUri(), group, username.getOrNull(), password.getOrNull(), connectTimeout.get(), requestTimeout.get())
        logger.lifecycle("Received staging profile id: '{}' for package {} (repository {})", profile, group, repositoryAlias.get())
    }

    class StagingEnabled :
        Spec<Task>,
        Serializable {
        override fun isSatisfiedBy(task: Task): Boolean =
            (task as StagingProfileDiagnostic).useStaging.get()
    }
}
