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
import groovy.lang.MissingMethodException
import org.codehaus.groovy.runtime.metaclass.MissingMethodExecutionFailed
import org.gradle.api.Action
import org.gradle.api.InvalidActionClosureException
import org.gradle.util.Configurable

/**
 * Exact copy of [org.gradle.util.internal.ClosureBackedAction] from Gradle 7.6, except.
 *
 * - removed everything except the methods we are using
 * - removed deprecation warnings
 * - converted to Kotlin
 *
 * This is needed because the class is not present in Gradle 5.
 */
internal class ClosureBackedAction<T : Any> @JvmOverloads constructor(private val closure: Closure<*>, private val resolveStrategy: Int = Closure.DELEGATE_FIRST, private val configurableAware: Boolean = true) : Action<T> {
    override fun execute(delegate: T) {
        try {
            if (configurableAware && delegate is Configurable<*>) {
                (delegate as Configurable<*>).configure(closure)
            } else {
                val copy = closure.clone() as Closure<*>
                copy.resolveStrategy = resolveStrategy
                copy.delegate = delegate
                if (copy.maximumNumberOfParameters == 0) {
                    copy.call()
                } else {
                    copy.call(delegate)
                }
            }
        } catch (e: MissingMethodException) {
            if (e.type == closure.javaClass && e.method == "doCall") {
                throw InvalidActionClosureException(closure, delegate)
            }
            throw MissingMethodExecutionFailed(e.method, e.type, e.arguments, e.isStatic, e)
        }
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || javaClass != o.javaClass) {
            return false
        }
        val that = o as ClosureBackedAction<*>
        return configurableAware == that.configurableAware && resolveStrategy == that.resolveStrategy && closure == that.closure
    }

    override fun hashCode(): Int {
        var result = closure.hashCode()
        result = 31 * result + if (configurableAware) 1 else 0
        result = 31 * result + resolveStrategy
        return result
    }

    companion object {
        fun <T : Any> of(closure: Closure<*>): ClosureBackedAction<T> {
            return ClosureBackedAction(closure)
        }

        fun <T : Any> execute(delegate: T, closure: Closure<*>) {
            ClosureBackedAction<T>(closure).execute(delegate)
        }
    }
}
