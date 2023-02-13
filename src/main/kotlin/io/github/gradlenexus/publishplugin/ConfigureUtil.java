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

package io.github.gradlenexus.publishplugin;

import groovy.lang.Closure;
import org.codehaus.groovy.runtime.GeneratedClosure;
import org.gradle.api.Action;
import org.gradle.util.Configurable;
import org.gradle.internal.Actions;
import org.gradle.internal.metaobject.ConfigureDelegate;
import org.gradle.util.internal.ClosureBackedAction;

import javax.annotation.Nullable;

/**
 * Exact copy of {@link org.gradle.util.ConfigureUtil} from Gradle 7.6, except
 * non-deprecated and with only the methods we are using.
 */
class ConfigureUtil {
    /**
     * <p>Configures {@code target} with {@code configureClosure}, via the {@link Configurable} interface if necessary.</p>
     *
     * <p>If {@code target} does not implement {@link Configurable} interface, it is set as the delegate of a clone of
     * {@code configureClosure} with a resolve strategy of {@code DELEGATE_FIRST}.</p>
     *
     * <p>If {@code target} does implement the {@link Configurable} interface, the {@code configureClosure} will be passed to
     * {@code delegate}'s {@link Configurable#configure(Closure)} method.</p>
     *
     * @param configureClosure The configuration closure
     * @param target The object to be configured
     * @return The delegate param
     */
    public static <T> T configure(@Nullable Closure configureClosure, T target) {
        if (configureClosure == null) {
            return target;
        }

        if (target instanceof Configurable) {
            ((Configurable) target).configure(configureClosure);
        } else {
            configureTarget(configureClosure, target, new ConfigureDelegate(configureClosure, target));
        }

        return target;
    }

    /**
     * Creates an action that uses the given closure to configure objects of type T.
     */
    public static <T> Action<T> configureUsing(@Nullable final Closure configureClosure) {
        if (configureClosure == null) {
            return Actions.doNothing();
        }

        return new WrappedConfigureAction<T>(configureClosure);
    }

    /**
     * Called from an object's {@link Configurable#configure} method.
     */
    public static <T> T configureSelf(@Nullable Closure configureClosure, T target, ConfigureDelegate closureDelegate) {
        if (configureClosure == null) {
            return target;
        }

        configureTarget(configureClosure, target, closureDelegate);
        return target;
    }

    private static <T> void configureTarget(Closure configureClosure, T target, ConfigureDelegate closureDelegate) {
        if (!(configureClosure instanceof GeneratedClosure)) {
            new ClosureBackedAction<T>(configureClosure, Closure.DELEGATE_FIRST, false).execute(target);
            return;
        }

        // Hackery to make closure execution faster, by short-circuiting the expensive property and method lookup on Closure
        Closure withNewOwner = configureClosure.rehydrate(target, closureDelegate, configureClosure.getThisObject());
        new ClosureBackedAction<T>(withNewOwner, Closure.OWNER_ONLY, false).execute(target);
    }

    /**
     * Wrapper configure action.
     *
     * @param <T> the action type.
     */
    public static class WrappedConfigureAction<T> implements Action<T> {
        private final Closure configureClosure;

        WrappedConfigureAction(Closure configureClosure) {
            this.configureClosure = configureClosure;
        }

        @Override
        public void execute(T t) {
            configure(configureClosure, t);
        }
    }
}
