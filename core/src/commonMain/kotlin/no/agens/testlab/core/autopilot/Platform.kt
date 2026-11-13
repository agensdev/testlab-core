package no.agens.testlab.core.autopilot

/** SHA-1 of the UTF-8 bytes, lowercase hex (Python `hashlib.sha1(s.encode()).hexdigest()`). */
internal expect fun sha1Hex(s: String): String

/** Python's `round(x, digits)`: half-even on the exact binary value of the double. */
expect fun pyRound(x: Double, digits: Int): Double
