# Settings support — unreleased release-note draft

**Review draft for an unreleased implementation.** No released feature version, published migration URL or completed support declaration is asserted. Resolve the pending public-contract decisions and final qualification before using these notes for a release.

The Nexus Publish plugin adds optional Settings application through the existing `io.github.gradle-nexus.publish-plugin` ID. Each project prepares its native Maven or Ivy publications locally; root-owned tasks collect the selected bundles and perform repository operations. Empty and nonpublishing projects participate automatically without applying Java, publishing or signing plugins.

Existing root Project application remains available with its tasks, hooks, defaults and supported runtimes. A build migrates explicitly by moving public plugin application and Nexus repository configuration into Settings while retaining project-local publication, artifact and signing configuration. Cache and isolation flags do not select the implementation. Applying the public plugin to both Settings and Project fails before legacy wiring.

The Settings implementation provides selected publication unions, native artifact and metadata preparation, root-owned remote/session hooks, staging profile diagnostics and independent explicit-ID recovery. Remote operations run afresh on configuration-cache reuse. Selected failed or skipped work cannot supply a stale success receipt for later lifecycle operations.

Migration changes need attention:

- Configure Settings values, repository membership and Ivy layout before the plugin's `settingsEvaluated` snapshot. Provider values are sampled and finalized there; later setters can fail even if they assign the same value.
- Native `PublishToMavenRepository` and `PublishToIvyRepository` hooks affect local file export. Use `NexusRemotePublish` for actual remote upload hooks.
- Use `NexusRepositorySession` for shared acquisition/adoption prerequisites. Session actions can run for both explicit acquisition and automatic adoption, and also for direct nonstaging handle preparation; they are not one callback per remote create.
- Root policy selects staging. Explicit `useStaging=false` uses `snapshotRepositoryUrl` even for release publication coordinates.
- Any configured custom Ivy layout suppresses Ivy Gradle module metadata before signing, including for another selected default-layout Ivy repository. Maven module metadata is unaffected.

The final meaning of root `publishTo<Repository>` and destination-specific publication omission remain pending contract decisions. This draft does not promise silent skipping of disabled selected exports or claim publication-model removal as an equivalent omission mechanism. See the [migration review draft](settings-plugin.md) for the implemented behavior and remaining example qualification.

Local qualification covers Settings consumers on Gradle 9.7.1 and 9.8.1 with Java 17, strict configuration cache and isolated projects. Actual Nexus 2 OSS 2.15.2-03 direct-deployment checks passed for signed Maven/Ivy releases, custom Ivy layouts, Maven and Ivy snapshots, selected multiproject publications and plugin markers, with independent consumers. Existing legacy qualification remains separate; the final coherent modern suite and complete legacy matrix must be reviewed before release.

Licensed Nexus 2 Pro staging remains unqualified. Local Linux checks do not establish hosted CI or Windows/macOS results. There is no claim of Nexus 3 or Maven Central Portal staging support. Other publishing backends remain follow-ups.

A real stable migration URL must be published and verified before adding the legacy migration warning. These draft documents do not supply that URL, change existing README instructions or announce a removal deadline. Publication and release remain separate actions.
