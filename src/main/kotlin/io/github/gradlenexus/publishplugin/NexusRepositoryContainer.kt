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
import org.gradle.internal.Actions

interface NexusRepositoryContainer : NamedDomainObjectContainer<NexusRepository> {
    @Suppress("unused")
    val s01 get() = NexusHost.S01

    fun sonatype() = sonatype(NexusHost.OSS, Actions.doNothing())
    fun sonatype(closure: Closure<*>) = sonatype(NexusHost.OSS, closure)
    fun sonatype(action: Action<in NexusRepository>) = sonatype(NexusHost.OSS, action)

    fun sonatype(nexusHost: NexusHost) = sonatype(nexusHost, Actions.doNothing())
    fun sonatype(nexusHost: NexusHost, closure: Closure<*>): NexusRepository
    fun sonatype(nexusHost: NexusHost, action: Action<in NexusRepository>): NexusRepository
}
