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
import org.gradle.api.artifacts.repositories.IvyArtifactRepository
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.provider.Provider
import java.net.URI

/**
 * Contains extension methods that mimic Gradle 6+ API for ArtifactRepository
 */

fun ArtifactRepository.setUrl(provider: Provider<URI>) {
    when (this) {
        is MavenArtifactRepository -> {
            this.setUrl(provider)
        }

        is IvyArtifactRepository -> {
            this.setUrl(provider)
        }
    }
}

val ArtifactRepository.url: String?
    get() = when (this) {
        is MavenArtifactRepository -> {
            this.url.toString()
        }

        is IvyArtifactRepository -> {
            this.toString()
        }

        else -> null
    }
