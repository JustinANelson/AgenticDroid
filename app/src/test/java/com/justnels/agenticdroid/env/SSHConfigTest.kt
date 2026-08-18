package com.justnels.agenticdroid.env

import org.junit.Test

class SSHConfigTest {
    private val fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnknownHostWithoutPinnedFingerprint() {
        SSHConfig("example.test", 22, "user", "password", "")
    }

    @Test
    fun acceptsPasswordAndPrivateKeyProfiles() {
        SSHConfig("example.test", 22, "user", "password", fingerprint)
        SSHConfig(
            host = "example.test",
            username = "user",
            hostKeyFingerprint = fingerprint,
            authType = SSHAuthType.PRIVATE_KEY,
            privateKeyContent = "-----BEGIN OPENSSH PRIVATE KEY-----\ntest\n-----END OPENSSH PRIVATE KEY-----"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPrivateKeyProfileWithoutKeyMaterial() {
        SSHConfig(
            host = "example.test",
            username = "user",
            hostKeyFingerprint = fingerprint,
            authType = SSHAuthType.PRIVATE_KEY
        )
    }
}
