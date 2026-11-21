package mc.xalitoria.menu.paper.editor

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption of what goes through a relay: AES-256-GCM, a message being 12 bytes of iv and then the cipher text with its tag. The
 * associated data is `xm1:<session>:<direction>` (`q` for a request, `a` for an answer), so a message made for one session or one way
 * cannot be passed off as another. The web editor (`relay.js`) makes and reads the same format.
 *
 * Short edit links carry a 12-character base64url code (9 random bytes = 72 bits) in the URL fragment (`#<code>`). [derive] expands
 * that code through PBKDF2-HMAC-SHA256 into an opaque 22-character relay session id and a 256-bit AES-GCM key so the relay never sees
 * either the code or the encryption key.
 */
internal object RelayCrypto {
    private val random = SecureRandom()
    private const val IV = 12
    const val CODE_LENGTH = 12
    val CODE_PATTERN = Regex("[A-Za-z0-9_-]{$CODE_LENGTH}")
    private const val PBKDF2_ITERATIONS = 100_000
    private val PBKDF2_SALT = "xalimenu:session:v2".toByteArray(Charsets.UTF_8)

    /** The relay session id (22 base64url chars) and AES-256-GCM key derived from a short 12-char link code. */
    data class Derived(val sessionId: String, val key: SecretKeySpec)

    /** A 12-character URL-safe base64 code (9 random bytes, 72 bits of entropy) for a short editor link. */
    fun newCode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(9).also(random::nextBytes))

    /** Derives the opaque relay session id and AES-256-GCM key from a short [code]. Matches `deriveCode` in `web/src/relay.js`. */
    fun derive(code: String): Derived {
        val spec = PBEKeySpec(code.toCharArray(), PBKDF2_SALT, PBKDF2_ITERATIONS, 48 * 8)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val sessionId = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.copyOfRange(0, 16))
        return Derived(sessionId, SecretKeySpec(raw.copyOfRange(16, 48), "AES"))
    }

    /** A new 256-bit key, as a legacy link carries it (base64url). */
    fun newKey(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    fun keyOf(text: String) = SecretKeySpec(Base64.getUrlDecoder().decode(text), "AES")

    private fun cipher(mode: Int, key: SecretKeySpec, iv: ByteArray, session: String, direction: String) =
        Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(mode, key, GCMParameterSpec(128, iv))
            it.updateAAD("xm1:$session:$direction".toByteArray())
        }

    fun seal(key: SecretKeySpec, session: String, direction: String, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV).also(random::nextBytes)
        return iv + cipher(Cipher.ENCRYPT_MODE, key, iv, session, direction).doFinal(plain)
    }

    /** What [seal] made, or null if [bytes] was not made with this key, session and direction (or was changed on the way). */
    fun open(key: SecretKeySpec, session: String, direction: String, bytes: ByteArray): ByteArray? {
        if (bytes.size < IV + 16) return null
        return try {
            cipher(Cipher.DECRYPT_MODE, key, bytes.copyOfRange(0, IV), session, direction).doFinal(bytes, IV, bytes.size - IV)
        } catch (e: Exception) {
            null
        }
    }
}
