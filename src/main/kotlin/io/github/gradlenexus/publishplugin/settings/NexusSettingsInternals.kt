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

package io.github.gradlenexus.publishplugin.settings

import org.gradle.api.GradleException

/**
 * Internal implementation support, not a supported settings DSL customization API.
 */
object NexusSettingsInternals {
    @JvmStatic
    fun freeze(settings: NexusPublishSettings) {
        settings.useStaging.finalizeValue()
        settings.packageGroup.finalizeValue()
        settings.repositoryDescription.finalizeValue()
        settings.clientTimeout.finalizeValue()
        settings.connectTimeout.finalizeValue()
        settings.excludedProjects.finalizeValue()
        settings.transitionCheckOptions.maxRetries.finalizeValue()
        settings.transitionCheckOptions.delayBetween.finalizeValue()
        for (repository in settings.repositories) {
            repository.nexusUrl.finalizeValue()
            repository.snapshotRepositoryUrl.finalizeValue()
            repository.username.finalizeValue()
            repository.password.finalizeValue()
            repository.publicationType.finalizeValue()
            repository.stagingProfileId.finalizeValue()
            repository.stagingRepositoryId.finalizeValue()
            repository.allowInsecureProtocol.finalizeValue()
            repository.freezeLayout()
        }
        settings.repositories.whenObjectAdded {
            throw frozen("repositories")
        }
        settings.repositories.whenObjectRemoved {
            throw frozen("repositories")
        }
    }

    @JvmStatic
    fun frozen(category: String): GradleException =
        GradleException("NEXUS_SETTINGS_FROZEN: $category. Configure nexusPublishing directly in the settings script before its settingsEvaluated snapshot.")
}
