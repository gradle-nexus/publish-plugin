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

package io.github.gradlenexus.publishplugin.modern

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.util.UUID

/** Exercises the packaged production Settings bootstrap. */
internal class ProductionBootstrapTest {
    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    @Tag("settings-smoke")
    fun `settings bootstraps every local project with detached nonsecret state`(runtime: ModernRuntime) {
        val project = project()
        File(project, "settings.gradle").writeText(
            settings() + """
            rootProject.name = 'bootstrap'
            include ':z', ':a:nested', ':empty'
            nexusPublishing {
                excludedProjects.add(':z')
                repositories {
                    create('probe') {
                        nexusUrl = uri('https://endpoint-boundary-sentinel.invalid/')
                        username = 'username-boundary-sentinel'
                        password = 'password-boundary-sentinel'
                        publicationType = io.github.gradlenexus.publishplugin.settings.NexusRepositorySettings.PublicationType.IVY
                        ivyPatternLayout { artifact 'owned/[artifact].[ext]'; ivy 'owned/ivy.xml'; m2compatible = true }
                    }
                }
            }
            """.trimIndent()
        )
        listOf("", "z", "a", "a/nested", "empty").forEach { path ->
            File(project, path).mkdirs()
            File(File(project, path), "build.gradle").writeText(producerScript())
        }
        ModernGradleBuild(project, false, runtime).storeAndReuse("probeBootstrap") { result ->
            listOf(":", ":a", ":a:nested", ":empty", ":z").forEach { assertThat(result.output).contains("BOOTSTRAP_OK:$it") }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `root defaults remain lazy and credentials are root local String conventions`(runtime: ModernRuntime) {
        val project = project()
        File(project, "settings.gradle").writeText(
            settings() + """
            rootProject.name = 'late-root'
            include ':empty'
            nexusPublishing.repositories.create('probe')
            """.trimIndent()
        )
        File(project, "empty").mkdirs()
        File(project, "build.gradle").writeText(rootScript())
        val runner = ModernGradleBuild(project, false, runtime)
        runner.storeAndReuse("probeRoot") { assertThat(it.output).contains("ROOT_OK:late.group:late-root:2-SNAPSHOT:false:extra") }
        File(project, "gradle.properties").writeText("probeUsername=property-user\nprobePassword=property-password\n")
        runner.storeAndReuse("probeRoot") { assertThat(it.output).contains("ROOT_OK:late.group:late-root:2-SNAPSHOT:false:property") }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `earlier isolated project callback rejects before any legacy wiring`(runtime: ModernRuntime) {
        listOf(
            ":" to "project.pluginManager.apply('io.github.gradle-nexus.publish-plugin')",
            ":child" to "project.pluginManager.apply(io.github.gradlenexus.publishplugin.NexusPublishPlugin)",
            ":child" to "new io.github.gradlenexus.publishplugin.NexusPublishPlugin().apply(project)"
        ).forEach { (target, application) ->
            val project = project()
            File(project, "child").mkdirs()
            File(project, "settings.gradle").writeText(
                """
                buildscript { dependencies { classpath files('${jar()}') } }
                include ':child'
                gradle.lifecycle.beforeProject { project ->
                    if (project.path == '$target') {
                        assert project.gradle.sharedServices.registrations.findByName('stagingRepositoryUrlRegistry') == null
                        try {
                            $application
                            throw new AssertionError('Mixed application was accepted')
                        } catch (org.gradle.api.GradleException expected) {
                            def cause = expected
                            while (cause.cause != null) cause = cause.cause
                            assert cause.message.contains('Remove project application')
                            assert project.gradle.sharedServices.registrations.findByName('stagingRepositoryUrlRegistry') == null
                            assert project.extensions.findByName('nexusPublishing') == null
                            assert project.tasks.findByName('closeStagingRepositories') == null
                            throw new org.gradle.api.GradleException('EARLY_GUARD_CONFIRMED')
                        }
                    }
                }
                apply plugin: 'io.github.gradle-nexus.publish-plugin'
                """.trimIndent()
            )
            val task = if (target == ":") "help" else ":child:help"
            val result = ModernGradleBuild(project, false, runtime).failWithDiagnostic("EARLY_GUARD_CONFIRMED", task)
            assertThat(result.tasks).isEmpty()
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `typed Kotlin settings DSL loads packaged modern API`(runtime: ModernRuntime) {
        val project = project()
        File(project, "settings.gradle.kts").writeText(
            """
            plugins { id("io.github.gradle-nexus.publish-plugin") }
            nexusPublishing {
                repositories.create("sonatype") {
                    check(nexusUrl.get().toString() == "https://oss.sonatype.org/service/local/")
                    ivyPatternLayout { artifact("[artifact](.[ext])"); setM2compatible(true) }
                }
            }
            """.trimIndent()
        )
        ModernGradleBuild(project, true, runtime).storeAndReuse("help")
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `explicit settings values override root conventions without coercing numeric extras`(runtime: ModernRuntime) {
        val project = project()
        File(project, "settings.gradle").writeText(
            settings() + """
            nexusPublishing {
                useStaging = true
                packageGroup = 'explicit.group'
                repositoryDescription = 'explicit-description'
                repositories.create('probe') { username = 'explicit-user'; password = 'explicit-password' }
                repositories.create('numeric')
            }
            """.trimIndent()
        )
        File(project, "gradle.properties").writeText("probeUsername=ignored-user\nprobePassword=ignored-password\n")
        File(project, "build.gradle").writeText(
            """
            import io.github.gradlenexus.publishplugin.internal.modern.RootCoordinator
            abstract class OverridesProbe extends DefaultTask {
                @Input abstract Property<Boolean> getPolicyValid()
                @Internal abstract Property<String> getUsernameValue()
                @Internal abstract Property<String> getPasswordValue()
                @Internal abstract Property<String> getNumericUsername()
                @Internal abstract Property<String> getNumericPassword()
                @TaskAction void verify() {
                    assert policyValid.get()
                    if (usernameValue.get() != 'explicit-user' || passwordValue.get() != 'explicit-password') throw new GradleException('ROOT_EXPLICIT_CREDENTIAL_MISMATCH')
                    assert !numericUsername.present && !numericPassword.present
                    logger.lifecycle('OVERRIDES_OK')
                }
            }
            def coordinator = extensions.getByType(RootCoordinator)
            ext.numericUsername = 123; ext.numericPassword = 456
            group = 'ignored'; version = 'ignored-SNAPSHOT'
            tasks.register('probeOverrides', OverridesProbe) {
                policyValid.set(coordinator.useStaging.zip(coordinator.packageGroup) { staging, groupValue -> staging && groupValue == 'explicit.group' }
                    .zip(coordinator.repositoryDescription) { valid, description -> valid && description == 'explicit-description' })
                usernameValue.set(coordinator.repositories.find { it.name == 'probe' }.username)
                passwordValue.set(coordinator.repositories.find { it.name == 'probe' }.password)
                numericUsername.set(coordinator.repositories.find { it.name == 'numeric' }.username)
                numericPassword.set(coordinator.repositories.find { it.name == 'numeric' }.password)
            }
            """.trimIndent()
        )
        ModernGradleBuild(project, false, runtime).storeAndReuse("probeOverrides") { assertThat(it.output).contains("OVERRIDES_OK") }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `unsupported target fails without linking legacy configuration`(runtime: ModernRuntime) {
        val project = project()
        File(project, "settings.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${jar()}') } }
            new io.github.gradlenexus.publishplugin.NexusPublishPlugin().apply(new Object())
            """.trimIndent()
        )
        val result = ModernGradleBuild(project, false, runtime).failWithDiagnostic("supports only Settings or Project targets", "help")
        assertThat(result.tasks).isEmpty()
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `settings credential providers invalidate cache and deliver exact refreshed values`(runtime: ModernRuntime) {
        val project = project()
        File(project, "settings.gradle").writeText(
            settings() + """
            def environment = providers
            nexusPublishing.repositories.create('probe') {
                username.set(environment.environmentVariable('BoundaryFixture_USERNAME'))
                password.set(environment.environmentVariable('BoundaryFixture_PASSWORD'))
            }
            """.trimIndent()
        )
        File(project, "build.gradle").writeText(
            """
            import io.github.gradlenexus.publishplugin.internal.modern.RootCoordinator
            abstract class RefreshProbe extends DefaultTask {
                @Internal abstract Property<String> getUsernameValue()
                @Internal abstract Property<String> getPasswordValue()
                @Input abstract Property<String> getGeneration()
                @TaskAction void verify() {
                    def suffix = generation.get()
                    if (usernameValue.get() != 'refresh-user-' + suffix || passwordValue.get() != 'refresh-password-' + suffix) {
                        throw new GradleException('ROOT_PROVIDER_REFRESH_MISMATCH')
                    }
                    logger.lifecycle('PROVIDER_REFRESH_OK:' + suffix)
                }
            }
            def coordinator = extensions.getByType(RootCoordinator)
            tasks.register('probeRefresh', RefreshProbe) {
                usernameValue.set(coordinator.repositories[0].username)
                passwordValue.set(coordinator.repositories[0].password)
                generation.set(providers.environmentVariable('BoundaryFixture_USERNAME').map { it.endsWith('one') ? 'one' : 'two' })
            }
            """.trimIndent()
        )
        listOf("one", "two").forEach { generation ->
            val runner = ModernGradleBuild(project, false, runtime, mapOf("BoundaryFixture_USERNAME" to "refresh-user-$generation", "BoundaryFixture_PASSWORD" to "refresh-password-$generation"))
            runner.storeAndReuse("probeRefresh") { assertThat(it.output).contains("PROVIDER_REFRESH_OK:$generation") }
        }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `precompiled convention sees automatic producer and public convention rejects before legacy wiring`(runtime: ModernRuntime) {
        val project = project()
        File(project, "settings.gradle").writeText(settings())
        val buildSrc = File(project, "buildSrc").apply { mkdirs() }
        File(buildSrc, "settings.gradle.kts").writeText("rootProject.name = \"bootstrap-conventions\"\n")
        File(buildSrc, "build.gradle.kts").writeText(
            """
            plugins { `kotlin-dsl` }
            repositories { mavenCentral(); gradlePluginPortal() }
            dependencies { implementation(files("${jar()}")) }
            """.trimIndent()
        )
        val scripts = File(buildSrc, "src/main/kotlin").apply { mkdirs() }
        // The producer is applied by Settings. The convention only needs a compile-time API classpath.
        File(scripts, "producer-convention.gradle.kts").writeText(
            """
            import io.github.gradlenexus.publishplugin.internal.modern.ProducerState
            check(extensions.getByType<ProducerState>().projectPath == path)
            tasks.register("conventionProbe")
            """.trimIndent()
        )
        File(scripts, "legacy-convention.gradle.kts").writeText("plugins { id(\"io.github.gradle-nexus.publish-plugin\") }\n")
        File(project, "build.gradle.kts").writeText("plugins { id(\"producer-convention\") }\n")
        val runner = ModernGradleBuild(project, false, runtime)
        runner.storeAndReuse("conventionProbe")
        File(project, "build.gradle.kts").delete()
        File(project, "build.gradle").writeText(
            """
            assert gradle.sharedServices.registrations.findByName('stagingRepositoryUrlRegistry') == null
            def taskNamesBeforeMixedApplication = tasks.names.toSet()
            try {
                pluginManager.apply('legacy-convention')
                throw new AssertionError('Mixed convention was accepted')
            } catch (Exception expected) {
                def cause = expected
                while (cause.cause != null) cause = cause.cause
                assert cause.message.contains('Remove project application')
                assert gradle.sharedServices.registrations.findByName('stagingRepositoryUrlRegistry') == null
                assert extensions.findByName('nexusPublishing') == null
                assert tasks.names.toSet() == taskNamesBeforeMixedApplication
                throw new GradleException('CONVENTION_GUARD_CONFIRMED')
            }
            """.trimIndent()
        )
        val result = runner.failWithDiagnostic("CONVENTION_GUARD_CONFIRMED", "help")
        assertThat(result.tasks.map { it.path }).allMatch { it.startsWith(":buildSrc:") }
    }

    @ParameterizedTest
    @EnumSource(ModernRuntime::class)
    fun `outer settings mode leaves legacy included build independent`(runtime: ModernRuntime) {
        val project = project()
        File(project, "settings.gradle").writeText(settings() + "includeBuild 'included'\n")
        val included = File(project, "included").apply { mkdirs() }
        File(included, "settings.gradle").writeText("rootProject.name = 'legacy-included'\n")
        File(included, "build.gradle").writeText(
            """
            buildscript { dependencies { classpath files('${jar()}') } }
            apply plugin: 'io.github.gradle-nexus.publish-plugin'
            assert extensions.findByName('nexusPublishing') != null
            assert gradle.sharedServices.registrations.findByName('io.github.gradle-nexus.publish-plugin.settings-mode') == null
            abstract class LegacyWitness extends DefaultTask {
                @TaskAction void verify() { logger.lifecycle('INCLUDED_LEGACY_OK') }
            }
            tasks.register('legacyWitness', LegacyWitness)
            """.trimIndent()
        )
        ModernGradleBuild(project, false, runtime).storeAndReuse("help", ":included:legacyWitness") { assertThat(it.output).contains("INCLUDED_LEGACY_OK") }
    }

    private fun producerScript() =
        """
        import io.github.gradlenexus.publishplugin.internal.modern.ProducerState
        abstract class BootstrapProbe extends DefaultTask {
            @Input abstract Property<String> getWitness()
            @TaskAction void verify() { logger.lifecycle(witness.get()) }
        }
        def state = extensions.getByType(ProducerState)
        assert extensions.findByName('nexusPublishing') == null
        assert !plugins.hasPlugin('java') && !plugins.hasPlugin('publishing') && !plugins.hasPlugin('signing')
        assert state.bootstrap.projectPaths == [':', ':a', ':a:nested', ':empty', ':z']
        assert state.excluded == (path == ':z')
        assert state.bootstrap.suppressIvyModuleMetadata
        // Audit only plugin-owned serializable state, without traversing Gradle infrastructure.
        def bytes = new ByteArrayOutputStream()
        new ObjectOutputStream(bytes).withCloseable { it.writeObject(state) }
        def serialized = bytes.toString('ISO-8859-1')
        assert !serialized.contains('boundary-sentinel') && !serialized.contains('https://')
        tasks.register('probeBootstrap', BootstrapProbe) { witness.set('BOOTSTRAP_OK:' + state.projectPath) }
        """.trimIndent()

    private fun rootScript() =
        """
        import io.github.gradlenexus.publishplugin.internal.modern.RootCoordinator
        abstract class RootProbe extends DefaultTask {
            @Input abstract Property<String> getDescriptionValue()
            @Input abstract Property<String> getGroupValue()
            @Input abstract Property<Boolean> getStagingValue()
            @Internal abstract Property<String> getUsernameValue()
            @Internal abstract Property<String> getPasswordValue()
            @Input abstract Property<Boolean> getPropertyCredentials()
            @TaskAction void verify() {
                assert groupValue.get() == 'late.group'
                def source = propertyCredentials.get() ? 'property' : 'extra'
                if (usernameValue.get() != source + '-user' || passwordValue.get() != source + '-password') throw new GradleException('ROOT_CREDENTIAL_SOURCE_MISMATCH')
                logger.lifecycle('ROOT_OK:' + descriptionValue.get() + ':' + stagingValue.get() + ':' + source)
            }
        }
        def coordinator = extensions.getByType(RootCoordinator)
        assert coordinator.clientTimeout == java.time.Duration.ofMinutes(5)
        assert coordinator.connectTimeout == java.time.Duration.ofMinutes(5)
        assert coordinator.maxRetries == 60 && coordinator.delayBetween == java.time.Duration.ofSeconds(10)
        tasks.register('probeRoot', RootProbe) {
            descriptionValue.set(coordinator.repositoryDescription)
            groupValue.set(coordinator.packageGroup)
            stagingValue.set(coordinator.useStaging)
            usernameValue.set(coordinator.repositories.find { it.name == 'probe' }.username)
            passwordValue.set(coordinator.repositories.find { it.name == 'probe' }.password)
            propertyCredentials.set(providers.gradleProperty('probeUsername').map { true }.orElse(false))
        }
        tasks.named('probeRoot').get()
        afterEvaluate {
            group = 'late.group'; version = '2-SNAPSHOT'
            if (!project.hasProperty('probeUsername')) project.ext.probeUsername = 'extra-user'
            if (!project.hasProperty('probePassword')) project.ext.probePassword = 'extra-password'
        }
        """.trimIndent()

    private fun settings() =
        """
        buildscript { dependencies { classpath files('${jar()}') } }
        apply plugin: 'io.github.gradle-nexus.publish-plugin'
        """.trimIndent() + "\n"

    private fun jar() =
        ModernTestPaths.candidateJar.absolutePath.replace("\\", "/").replace("'", "\\'")
    private fun project(): File =
        File(ModernTestPaths.workspaceRoot, "production-bootstrap/${UUID.randomUUID()}/consumer").apply { mkdirs() }
}
