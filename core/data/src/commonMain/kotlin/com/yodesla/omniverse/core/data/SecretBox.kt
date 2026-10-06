package com.yodesla.omniverse.core.data

/**
 * Encrypts source configs (they hold provider usernames and passwords) before they reach the
 * database. Android uses a hardware-backed Keystore key (android/app KeystoreSecretBox); tests and
 * platforms without one use [None].
 *
 * Sealed values start with [PREFIX]; anything else is a legacy plaintext config from an older
 * build, which the repository re-seals on first read.
 */
interface SecretBox {
    fun seal(plain: String): String

    /** Null when the value can't be decrypted (e.g. the key was lost after a device restore). */
    fun open(sealed: String): String?

    companion object {
        const val PREFIX = "sealed:v1:"

        /** No encryption (tests, JVM tools). */
        val None: SecretBox = object : SecretBox {
            override fun seal(plain: String) = plain
            override fun open(sealed: String) = sealed
        }
    }
}
