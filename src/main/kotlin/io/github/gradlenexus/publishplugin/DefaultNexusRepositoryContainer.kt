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
import org.gradle.util.ConfigureUtil

@Suppress("UnstableApiUsage")
internal class DefaultNexusRepositoryContainer(delegate: NamedDomainObjectContainer<NexusRepository>) : NexusRepositoryContainer, NamedDomainObjectContainer<NexusRepository> by delegate {

    override fun sonatype(nexusHost: NexusHost, closure: Closure<*>): NexusRepository =
        sonatype(nexusHost, ConfigureUtil.configureUsing(closure))

    override fun sonatype(nexusHost: NexusHost, action: Action<in NexusRepository>): NexusRepository = create("sonatype") {
        action.execute(apply { this.nexusHost = nexusHost })
    }

    override fun sonatype(action: Action<in NexusRepository>): NexusRepository = create("sonatype", action::execute)

    override fun configure(configureClosure: Closure<*>): NamedDomainObjectContainer<NexusRepository> =
            ConfigureUtil.configureSelf(configureClosure, this, NamedDomainObjectContainerConfigureDelegate(configureClosure, this))
}
