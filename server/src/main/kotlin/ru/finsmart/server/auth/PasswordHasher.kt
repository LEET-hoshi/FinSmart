package ru.finsmart.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import at.favre.lib.crypto.bcrypt.LongPasswordStrategies

object PasswordHasher {
    private const val COST = 10

    private val longPasswordStrategy = LongPasswordStrategies.hashSha512(BCrypt.Version.VERSION_2A)
    private val hasher = BCrypt.with(longPasswordStrategy)
    private val verifier = BCrypt.verifyer(BCrypt.Version.VERSION_2A, longPasswordStrategy)
    private val dummyHash = hash("password-for-constant-time-login")

    fun hash(password: String): String =
        hasher.hashToString(COST, password.toCharArray())

    fun verify(password: String, hash: String): Boolean =
        verifier.verify(password.toCharArray(), hash).verified

    fun verifyAgainstDummy(password: String) {
        verify(password, dummyHash)
    }
}
