plugins {
    id("com.gradle.develocity") version "3.17.1"
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"
        uploadInBackground = false
        publishing.onlyIf { System.getenv("CI") != null }
    }
}

rootProject.name = "gradle-nexus-publish-plugin"
