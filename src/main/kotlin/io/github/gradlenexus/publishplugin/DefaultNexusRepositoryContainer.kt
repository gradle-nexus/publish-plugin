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

package io.github.gradlenexus.publishplugin

import groovy.lang.Closure
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.internal.NamedDomainObjectContainerConfigureDelegate
import org.gradle.util.GradleVersion
import java.net.URI
import javax.inject.Inject

internal open class DefaultNexusRepositoryContainer @Inject constructor(
    delegate: NamedDomainObjectContainer<NexusRepository>
) : NexusRepositoryContainer, NamedDomainObjectContainer<NexusRepository> by delegate {

    override fun sonatype(): NexusRepository =
        // `sonatype { }`, but in Kotlin 1.3 "New Inference" is not implemented yet, so we have to be explicit.
        // https://kotlinlang.org/docs/whatsnew14.html#new-more-powerful-type-inference-algorithm
        sonatype(Action {})

    override fun sonatype(action: Action<in NexusRepository>): NexusRepository = create("sonatype") {
        nexusUrl.set(URI.create("https://oss.sonatype.org/service/local/"))
        snapshotRepositoryUrl.set(URI.create("https://oss.sonatype.org/content/repositories/snapshots/"))
        action.execute(this)
    }

    override fun configure(configureClosure: Closure<*>): NamedDomainObjectContainer<NexusRepository> =
        if (GradleVersion.current().baseVersion < GradleVersion.version("7.6")) {
            // Keep using the old API on old Gradle versions.
            // It was deprecated in Gradle 7.1, but only from Gradle 7.6 it emits a deprecation warning.
            // https://docs.gradle.org/current/userguide/upgrading_version_7.html#org_gradle_util_reports_deprecations
            // Note: this will fail to compile when this project starts building on Gradle 9.0,
            // at which point, this will need to be fully resolved,
            // OR this call for older support will need to be removed OR reflective.
            @Suppress("DEPRECATION")
            org.gradle.util.ConfigureUtil.configureSelf(
                configureClosure,
                this,
                NamedDomainObjectContainerConfigureDelegate(configureClosure, this)
            )
        } else {
            // Keep using the new *internal* API on new Gradle versions.
            // At least until https://github.com/gradle/gradle/issues/23874 is resolved.
            // Introduced in Gradle 7.1, it's internal but stable up until the latest Gradle 8.0 at the time of writing.
            // The Gradle 7.1 version of this class is a verbatim copy of Gradle 8.0's version, but without the nagging.
            org.gradle.util.internal.ConfigureUtil.configureSelf(
                configureClosure,
                this,
                NamedDomainObjectContainerConfigureDelegate(configureClosure, this)
            )
        }
}
