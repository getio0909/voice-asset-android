plugins {
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnit()
}
