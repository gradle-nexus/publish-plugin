# Testing

Use the repository wrapper and installed JDKs 8 and 17. Modern model tests and all Settings consumers run on Java 17; legacy compatibility fixtures retain their selected JDK and Gradle versions. CI supplies installed toolchain paths explicitly and disables toolchain downloads.

| Command | Selection |
| --- | --- |
| `./gradlew check` | Ordinary unit tests, Java 17 modern model/protocol tests, packaged controls, Settings smoke tests, formatting and plugin validation. |
| `./gradlew legacyCheck` | Ordinary legacy unit tests, formatting and plugin validation; no Settings consumers or publication-proof preparation. |
| `./gradlew modernUnitTest` | Direct modern model and protocol tests; no consumer builds. |
| `./gradlew packagedTest` | Fixed ABI, legacy floor/current wrapper calls, isolated packaging/private dependencies, native class origins and strict runner controls. |
| `./gradlew settingsSmokeTest` | Six tagged Settings scenarios on Gradle 9.7.1 and 9.8.1: bootstrap, Maven/Ivy staged lifecycle, complete-union rejection, public session prerequisites and later-action receipt admission. |
| `./gradlew publicationTest` | Actual generated Maven/marker publications, source/documentation artifacts, public API accessors and packaged plugin resolution. Prepares an owned file repository. |
| `./gradlew settingsTest` | Complete non-server Settings/native qualification, including model, packaged, smoke and publication groups. Includes native parity/evidence classes as well as `Production*Test`. |
| `./gradlew compatTestJava11` | Legacy Project-plugin compatibility fixtures for the selected Stutter Java 11 matrix. Other matrix tasks keep their existing names. |

`build` includes the short `check` selection. CI runs that selection on Linux, macOS and Windows, and runs the complete Settings selection separately on Linux. Legacy-only CI rows use `assemble legacyCheck` plus their explicit compatibility task; they do not repeat Settings qualification.

For one smoke owner, use a filter such as `./gradlew settingsSmokeTest --tests '*ProductionIvyWorkflowTest'`. A filter on `settingsTest` limits that task's own broad cases; its model, packaged, smoke and publication dependencies still run. All surviving modern consumers use strict configuration cache and isolated projects; selecting a smaller group does not weaken those flags.

Modern consumer fixtures share a TestKit home within each owning test task and Gradle version to reuse runtime and script infrastructure. Project roots, configuration-cache entries, workflow receipts and HTTP fixtures remain independent. Fresh dependency-resolution controls select separate Gradle user homes with `--gradle-user-home`, while retaining the shared TestKit daemon registry. Their dependency caches start empty, so cached snapshot answers cannot replace repository reads. Signed Ivy native controls, the HTTP-policy table and the warm lifecycle table use independent included builds in a shared invocation; their assertions check each build's task outcomes, failure diagnostics, requests and receipts separately, with all rows and both runtime pins retained.

Real Nexus acceptance is separate from these commands. `realProductionOssTest` requires the disposable production harness and its explicit environment/signing-file inputs. It is excluded from `check` and `settingsTest`; ordinary loopback HTTP fixtures do not establish real-server acceptance.

The common session, lifecycle and direct-workflow Groovy fixtures read their endpoint from `fixture-endpoint.txt` through a Settings file-content provider. This keeps script text reusable while preserving configuration-cache input tracking. A real upload regression test changes only that file and verifies the new endpoint on cache store and reuse. Kotlin DSL source and precompiled convention checks remain enrolled.
