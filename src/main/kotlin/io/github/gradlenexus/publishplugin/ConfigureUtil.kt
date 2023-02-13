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
import org.codehaus.groovy.runtime.GeneratedClosure
import org.gradle.api.Action
import org.gradle.internal.metaobject.ConfigureDelegate
import org.gradle.util.Configurable

/**
 * Exact copy of [org.gradle.util.ConfigureUtil] from Gradle 7.6, except:

 * - removed everything except the methods we are using
 * - removed deprecation warnings
 * - converted to Kotlin
 */
internal object ConfigureUtil {
    /**
     *
     * Configures `target` with `configureClosure`, via the [Configurable] interface if necessary.
     *
     *
     * If `target` does not implement [Configurable] interface, it is set as the delegate of a clone of
     * `configureClosure` with a resolve strategy of `DELEGATE_FIRST`.
     *
     *
     * If `target` does implement the [Configurable] interface, the `configureClosure` will be passed to
     * `delegate`'s [Configurable.configure] method.
     *
     * @param configureClosure The configuration closure
     * @param target The object to be configured
     * @return The delegate param
     */
    fun <T : Any> configure(configureClosure: Closure<*>, target: T): T {
        if (target is Configurable<*>) {
            (target as Configurable<*>).configure(configureClosure)
        } else {
            configureTarget(configureClosure, target, ConfigureDelegate(configureClosure, target))
        }
        return target
    }

    /**
     * Creates an action that uses the given closure to configure objects of type T.
     */
    fun <T : Any> configureUsing(configureClosure: Closure<*>): Action<T> {
        return WrappedConfigureAction(configureClosure)
    }

    /**
     * Called from an object's [Configurable.configure] method.
     */
    fun <T : Any> configureSelf(configureClosure: Closure<*>?, target: T, closureDelegate: ConfigureDelegate): T {
        if (configureClosure == null) {
            return target
        }
        configureTarget(configureClosure, target, closureDelegate)
        return target
    }

    private fun <T : Any> configureTarget(configureClosure: Closure<*>, target: T, closureDelegate: ConfigureDelegate) {
        if (configureClosure !is GeneratedClosure) {
            ClosureBackedAction<T>(configureClosure, Closure.DELEGATE_FIRST, false).execute(target)
            return
        }

        // Hackery to make closure execution faster, by short-circuiting the expensive property and method lookup on Closure
        val withNewOwner = configureClosure.rehydrate(target, closureDelegate, configureClosure.thisObject)
        ClosureBackedAction<T>(withNewOwner, Closure.OWNER_ONLY, false).execute(target)
    }

    /**
     * Wrapper configure action.
     *
     * @param <T> the action type.
     </T> */
    class WrappedConfigureAction<T : Any> internal constructor(private val configureClosure: Closure<*>) : Action<T> {
        override fun execute(t: T) {
            configure(configureClosure, t)
        }
    }
}
