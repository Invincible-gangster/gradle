/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.integtests.fixtures

import org.gradle.integtests.fixtures.executer.GradleContextualExecuter
import org.gradle.internal.reflect.ClassInspector
import org.gradle.test.fixtures.ResettableExpectations
import org.junit.AssumptionViolatedException
import org.spockframework.runtime.extension.AbstractAnnotationDrivenExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.FeatureInfo

import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Predicate

import static org.gradle.integtests.fixtures.ToBeFixedForInstantExecution.Skip.FAILS_CLEANUP_ASSERTIONS
import static org.gradle.integtests.fixtures.ToBeFixedForInstantExecution.Skip.FLAKY

class ToBeFixedForInstantExecutionExtension extends AbstractAnnotationDrivenExtension<ToBeFixedForInstantExecution> {

    @Override
    void visitFeatureAnnotation(ToBeFixedForInstantExecution annotation, FeatureInfo feature) {

        if (GradleContextualExecuter.isNotInstant()) {
            return
        }

        if (!isEnabledSpec(annotation, feature)) {
            return
        }

        if (annotation.skip() == FLAKY) {
            feature.skipped = true
            return
        }

        if (annotation.skip() == FAILS_CLEANUP_ASSERTIONS) {
            def interceptor = new DisableCleanupAssertions()
            feature.interceptors.add(0, interceptor)
            feature.iterationInterceptors.add(0, interceptor)
            return
        }

        if (feature.isParameterized()) {
            feature.addInterceptor(new ToBeFixedIterationInterceptor(annotation.iterationMatchers()))
        } else {
            feature.getFeatureMethod().addInterceptor(new ToBeFixedInterceptor())
        }
    }

    private static class DisableCleanupAssertions implements IMethodInterceptor {

        @Override
        void intercept(IMethodInvocation invocation) throws Throwable {
            ignoreCleanupAssertionsOf(invocation)
            invocation.proceed()
        }
    }

    private static class ToBeFixedInterceptor implements IMethodInterceptor {

        @Override
        void intercept(IMethodInvocation invocation) throws Throwable {
            try {
                invocation.proceed()
            } catch (Throwable ex) {
                expectedFailure(ex)
                ignoreCleanupAssertionsOf(invocation)
                return
            }
            throw new UnexpectedSuccessException()
        }
    }

    private static class ToBeFixedIterationInterceptor implements IMethodInterceptor {

        private final String[] iterationMatchers

        ToBeFixedIterationInterceptor(String[] iterationMatchers) {
            this.iterationMatchers = iterationMatchers
        }

        @Override
        void intercept(IMethodInvocation invocation) throws Throwable {
            final AtomicBoolean pass = new AtomicBoolean(false)
            invocation.getFeature().getFeatureMethod().interceptors.add(
                0,
                new InnerIterationInterceptor(pass, iterationMatchers)
            )
            try {
                invocation.proceed()
            } catch (Throwable ex) {
                expectedFailure(ex)
                pass.set(true)
            }

            if (pass.get()) {
                throw new AssumptionViolatedException("Failed as expected.")
            } else {
                throw new UnexpectedSuccessException()
            }
        }

        private static class InnerIterationInterceptor implements IMethodInterceptor {
            private final AtomicBoolean pass
            private final String[] iterationMatchers

            InnerIterationInterceptor(AtomicBoolean pass, String[] iterationMatchers) {
                this.pass = pass
                this.iterationMatchers = iterationMatchers
            }

            @Override
            void intercept(IMethodInvocation invocation) throws Throwable {
                if (iterationMatches(iterationMatchers, invocation.iteration.name)) {
                    try {
                        invocation.proceed()
                    } catch (Throwable ex) {
                        expectedFailure(ex)
                        ignoreCleanupAssertionsOf(invocation)
                        pass.set(true)
                    }
                } else {
                    invocation.proceed()
                }
            }
        }
    }

    private static ignoreCleanupAssertionsOf(IMethodInvocation invocation) {
        def instance = invocation.instance
        if (instance instanceof AbstractIntegrationSpec) {
            instance.ignoreCleanupAssertions()
        }
        allInstanceFieldsOf(instance).forEach { Field specField ->
            if (ResettableExpectations.isAssignableFrom(specField.type)) {
                def server = specField.tap { it.accessible = true }.get(instance) as ResettableExpectations
                try {
                    server?.resetExpectations()
                } catch (Throwable error) {
                    error.printStackTrace()
                }
            }
        }
    }

    private static Collection<Field> allInstanceFieldsOf(instance) {
        ClassInspector.inspect(instance.getClass()).instanceFields
    }

    private static void expectedFailure(Throwable ex) {
        System.err.println("Failed with instant execution as expected:")
        ex.printStackTrace()
    }

    private static boolean isEnabledSpec(ToBeFixedForInstantExecution annotation, FeatureInfo feature) {
        isEnabledBottomSpec(annotation.bottomSpecs(), { it == feature.spec.bottomSpec.name })
    }

    static boolean isEnabledBottomSpec(String[] bottomSpecs, Predicate<String> specNamePredicate) {
        bottomSpecs.length == 0 || bottomSpecs.any { specNamePredicate.test(it) }
    }

    static boolean iterationMatches(String[] iterationMatchers, String iterationName) {
        isAllIterations(iterationMatchers) || iterationMatchers.any { iterationName.matches(it) }
    }

    static boolean isAllIterations(String[] iterationMatchers) {
        iterationMatchers.length == 0
    }

    static class UnexpectedSuccessException extends Exception {
        UnexpectedSuccessException() {
            super("Expected to fail with instant execution, but succeeded!")
        }
    }
}
