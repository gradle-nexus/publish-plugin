/*
 * Copyright 2018 the original author or authors.
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

package de.marcphilipp.gradle.nexus

import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property

import java.net.URI

@Suppress("UnstableApiUsage")
open class NexusPublishExtension(project: Project) {

    val useStaging: Property<Boolean> = project.objects.property()
    val serverUrl: Property<URI> = project.objects.property()
    val snapshotRepositoryUrl: Property<URI> = project.objects.property()
    val username: Property<String> = project.objects.property()
    val password: Property<String> = project.objects.property()
    val repositoryName: Property<String> = project.objects.property()
    val packageGroup: Property<String> = project.objects.property()
    val stagingProfileId: Property<String> = project.objects.property()

    init {
        useStaging.set(project.provider { !project.version.toString().endsWith("-SNAPSHOT") })
        serverUrl.set(URI.create("https://oss.sonatype.org/service/local/"))
        snapshotRepositoryUrl.set(URI.create("https://oss.sonatype.org/content/repositories/snapshots/"))
        username.set(project.provider { project.findProperty("nexusUsername") as String? })
        password.set(project.provider { project.findProperty("nexusPassword") as String? })
        repositoryName.set("nexus")
        packageGroup.set(project.provider { project.group.toString() })
    }

    companion object {
        internal const val NAME = "nexusPublishing"
    }
}
