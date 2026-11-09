package mc.xalitoria.menu.paper.editor

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The encryption of what goes through a relay: AES-256-GCM, a message being 12 bytes of iv and then the cipher text with its tag. The
 * associated data is `xm1:<session>:<direction>` (`q` for a request, `a` for an answer), so a message made for one session or one way
 * cannot be passed off as another. The web editor (`relay.js`) makes and reads the same format.
 */
internal object RelayCrypto {
    private val random = SecureRandom()
    private const val IV = 12

    /** A new key, as the link carries it (base64url). */
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
