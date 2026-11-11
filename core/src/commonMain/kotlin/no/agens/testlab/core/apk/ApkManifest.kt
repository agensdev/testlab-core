package no.agens.testlab.core.apk

/** What install and the build lists need from an APK's AndroidManifest.xml. */
data class ManifestInfo(
    val packageName: String,
    val versionCode: Long?,
    /** Null when the manifest points at a string resource instead of a literal. */
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    /** `scheme://host` of the intent filters' `<data>` elements (deeplinks and app links), in manifest order. */
    val links: List<String> = emptyList(),
)

class ApkFormatError(message: String) : RuntimeException(message)

/**
 * Reads the compiled (binary XML) AndroidManifest.xml of an APK: the string pool, the resource
 * map and the attributes of `<manifest>` and `<uses-sdk>`. Attributes are identified by their
 * framework resource id when the map has one (shrunk APKs can drop the names), else by name.
 * No SDK tools needed, so it works the same on every OS.
 */
object ApkManifest {
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 0x100

    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11

    // android.R.attr ids (stable across platform versions)
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_MIN_SDK = 0x0101020c
    private const val ATTR_TARGET_SDK = 0x01010270
    private const val ATTR_SCHEME = 0x01010027
    private const val ATTR_HOST = 0x01010028

    fun parse(axml: ByteArray): ManifestInfo {
        val r = Reader(axml)
        if (r.u16(0) != RES_XML_TYPE) throw ApkFormatError("not a binary XML manifest")
        var strings: List<String> = emptyList()
        var resIds = IntArray(0)
        var pkg: String? = null
        var versionCode: Long? = null
        var versionName: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        val links = LinkedHashSet<String>()
        var pos = r.u16(2)
        val end = minOf(r.u32(4).toInt(), axml.size)
        while (pos + 8 <= end) {
            val type = r.u16(pos)
            val headerSize = r.u16(pos + 2)
            val size = r.u32(pos + 4).toInt()
            if (size < 8 || pos + size > axml.size) throw ApkFormatError("bad chunk at $pos")
            when (type) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(r, pos)
                RES_XML_RESOURCE_MAP_TYPE -> resIds = IntArray((size - headerSize) / 4) { r.u32(pos + headerSize + it * 4).toInt() }
                RES_XML_START_ELEMENT_TYPE -> {
                    val ext = pos + headerSize
                    val name = strings.getOrNull(r.u32(ext + 4).toInt()) ?: ""
                    val attrStart = r.u16(ext + 8)
                    val attrSize = r.u16(ext + 10)
                    val attrCount = r.u16(ext + 12)
                    if (name == "data") {
                        var scheme: String? = null
                        var host: String? = null
                        for (i in 0 until attrCount) {
                            val a = ext + attrStart + i * attrSize
                            val nameIdx = r.u32(a + 4).toInt()
                            val attrName = strings.getOrNull(nameIdx) ?: ""
                            val resId = resIds.getOrElse(nameIdx) { 0 }
                            val raw = r.u32(a + 8).toInt()
                            val v = if (raw != -1) strings.getOrNull(raw) else if (r.u8(a + 15) == TYPE_STRING) strings.getOrNull(r.u32(a + 16).toInt()) else null
                            if (resId == ATTR_SCHEME || (resId == 0 && attrName == "scheme")) scheme = v
                            if (resId == ATTR_HOST || (resId == 0 && attrName == "host")) host = v
                        }
                        if (scheme != null && host != null && !host.startsWith("@")) links += "$scheme://$host"
                    }
                    if (name == "manifest" || name == "uses-sdk") {
                        for (i in 0 until attrCount) {
                            val a = ext + attrStart + i * attrSize
                            val nameIdx = r.u32(a + 4).toInt()
                            val attrName = strings.getOrNull(nameIdx) ?: ""
                            val resId = resIds.getOrElse(nameIdx) { 0 }
                            val raw = r.u32(a + 8).toInt()
                            val dataType = r.u8(a + 15)
                            val data = r.u32(a + 16)
                            fun str(): String? = when {
                                raw != -1 -> strings.getOrNull(raw)
                                dataType == TYPE_STRING -> strings.getOrNull(data.toInt())
                                else -> null
                            }
                            fun num(): Long? = when (dataType) {
                                TYPE_INT_DEC, TYPE_INT_HEX -> data
                                TYPE_STRING -> str()?.toLongOrNull()
                                else -> if (raw != -1) strings.getOrNull(raw)?.toLongOrNull() else null
                            }
                            when {
                                name == "manifest" && attrName == "package" && resId == 0 -> pkg = str()
                                resId == ATTR_VERSION_CODE || (resId == 0 && attrName == "versionCode") -> versionCode = num()
                                resId == ATTR_VERSION_NAME || (resId == 0 && attrName == "versionName") ->
                                    versionName = if (dataType == TYPE_REFERENCE) null else str()
                                resId == ATTR_MIN_SDK || (resId == 0 && attrName == "minSdkVersion") -> minSdk = num()?.toInt()
                                resId == ATTR_TARGET_SDK || (resId == 0 && attrName == "targetSdkVersion") -> targetSdk = num()?.toInt()
                            }
                        }
                    }
                }
            }
            pos += size
        }
        return ManifestInfo(pkg ?: throw ApkFormatError("the manifest has no package"), versionCode, versionName, minSdk, targetSdk, links.toList())
    }

    private fun readStringPool(r: Reader, chunk: Int): List<String> {
        val count = r.u32(chunk + 8).toInt()
        val flags = r.u32(chunk + 16).toInt()
        val stringsStart = r.u32(chunk + 20).toInt()
        val headerSize = r.u16(chunk + 2)
        val utf8 = flags and UTF8_FLAG != 0
        return List(count) { i ->
            val off = chunk + stringsStart + r.u32(chunk + headerSize + i * 4).toInt()
            if (utf8) {
                var p = off
                val (_, a) = r.len8(p); p += a // UTF-16 length, unused
                val (byteLen, b) = r.len8(p); p += b
                r.bytes.decodeToString(p, p + byteLen)
            } else {
                var p = off
                var len = r.u16(p); p += 2
                if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or r.u16(p); p += 2 }
                val chars = CharArray(len) { k -> r.u16(p + k * 2).toChar() }
                chars.concatToString()
            }
        }
    }

    private class Reader(val bytes: ByteArray) {
        fun u8(p: Int): Int = bytes[p].toInt() and 0xff
        fun u16(p: Int): Int = u8(p) or (u8(p + 1) shl 8)
        fun u32(p: Int): Long = (u16(p).toLong() or (u16(p + 2).toLong() shl 16))
        /** A UTF-8 pool length: one byte, or two when the high bit is set. Returns (value, bytes used). */
        fun len8(p: Int): Pair<Int, Int> {
            val b0 = u8(p)
            return if (b0 and 0x80 != 0) (((b0 and 0x7f) shl 8) or u8(p + 1)) to 2 else b0 to 1
        }
    }
}
