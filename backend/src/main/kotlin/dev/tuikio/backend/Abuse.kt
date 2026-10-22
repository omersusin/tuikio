package dev.tuikio.backend

import dev.tuikio.community.ProofOfWork
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Wall clock in milliseconds; injectable so tests can move time. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

internal fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(key, "HmacSHA256"))
    doFinal(data)
}

internal fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

/**
 * Turns client addresses into short-lived pseudonyms for rate limiting. The key is random
 * per process and the day is mixed in, so the hashes can't be reversed with a lookup table
 * or linked across days. Neither addresses nor their hashes are written to disk or logs.
 */
class IpHasher(private val clock: Clock, private val key: ByteArray = randomBytes(32)) {
    fun hash(ip: String): String {
        val day = clock.now() / 86_400_000L
        return hmac(key, "$day|$ip".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}

/** Fixed-window counters per (bucket, client). In memory only. */
class RateLimiter(private val clock: Clock) {
    private class Window(val start: Long, var count: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    fun allow(bucket: String, client: String, limit: Int, windowMs: Long = 3_600_000L): Boolean {
        val now = clock.now()
        if (windows.size > 200_000) windows.entries.removeIf { now - it.value.start >= windowMs }
        val w = windows.compute("$bucket|$client") { _, old -> if (old == null || now - old.start >= windowMs) Window(now, 0) else old }!!
        synchronized(w) {
            if (w.count >= limit) return false
            w.count++
            return true
        }
    }
}

/**
 * Stateless, server-signed proof-of-work challenges: `v1.<expiry>.<random>.<mac>`. A solved
 * challenge can be spent once; spent ones are remembered until they expire.
 */
class Challenges(private val clock: Clock, val bits: Int, private val ttlMs: Long = 10 * 60_000L, private val key: ByteArray = randomBytes(32)) {
    sealed class Result {
        object Ok : Result()
        data class Rejected(val reason: String) : Result()
    }

    private val spent = ConcurrentHashMap<String, Long>()
    private val enc = Base64.getUrlEncoder().withoutPadding()

    fun issue(): Pair<String, Long> {
        val expiry = (clock.now() + ttlMs) / 1000
        val body = "v1.$expiry.${enc.encodeToString(randomBytes(12))}"
        return "$body.${mac(body)}" to expiry
    }

    fun redeem(challenge: String, nonce: Long): Result {
        val parts = challenge.split('.')
        if (parts.size != 4 || parts[0] != "v1") return Result.Rejected("Malformed challenge.")
        val body = parts.take(3).joinToString(".")
        if (!MessageDigest.isEqual(mac(body).toByteArray(), parts[3].toByteArray())) return Result.Rejected("Unknown challenge.")
        val expiry = parts[1].toLongOrNull() ?: return Result.Rejected("Malformed challenge.")
        val now = clock.now()
        if (expiry * 1000 < now) return Result.Rejected("The anti-spam check expired; please try again.")
        if (!ProofOfWork.verify(challenge, nonce, bits)) return Result.Rejected("The anti-spam check didn't pass.")
        if (spent.size > 100_000) spent.entries.removeIf { it.value * 1000 < now }
        if (spent.putIfAbsent(challenge, expiry) != null) return Result.Rejected("That anti-spam check was already used.")
        return Result.Ok
    }

    private fun mac(body: String) = enc.encodeToString(hmac(key, body.toByteArray()).copyOf(16))
}
