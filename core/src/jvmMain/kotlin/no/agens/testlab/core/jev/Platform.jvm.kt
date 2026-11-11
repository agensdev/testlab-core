package no.agens.testlab.core.jev

import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

internal actual fun sha1Hex(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.encodeToByteArray()).joinToString("") { "%02x".format(it) }

actual fun pyRound(x: Double, digits: Int): Double {
    if (x.isNaN() || x.isInfinite()) return x
    return BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
}
