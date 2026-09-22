package com.agnetix.harnax.common.cli

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the unix mode of every entry straight from the zip central directory.
 *
 * [java.util.zip.ZipFile] parses the same structure but keeps the external attributes to itself, so
 * the permission bits a package was packed with are invisible to it. That matters here: a payload
 * binary extracted without its execute bit still builds into an image, and the only symptom is the
 * check command exiting 126/127 — a failure that reads like "wrong command", not like "lost mode".
 *
 * Only the names and attribute words are read; entry content still goes through [CliPackageArchive],
 * which lets the JDK handle compression, encoding and zip64 data descriptors.
 */
internal object ZipCentralDirectoryModes {
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val CENTRAL_HEADER_FIXED_BYTES = 46
    private const val MAX_EOCD_SEARCH = 65_557
    private const val CREATOR_UNIX = 3

    /** name → stored unix `st_mode`: the file type bits plus the 12 permission bits. */
    fun read(file: File): Map<String, Int> {
        RandomAccessFile(file, "r").use { raf ->
            val eocd = findEndOfCentralDirectory(raf)
                ?: throw IllegalArgumentException("Not a readable zip archive (no end-of-central-directory record): ${file.name}")
            raf.seek(eocd + 16)
            val centralOffset = readLittleInt(raf)
            if (centralOffset == -1) {
                throw IllegalArgumentException(
                    "zip64 end-of-central-directory locator is not supported: ${file.name}",
                )
            }
            raf.seek(centralOffset.toLong())
            val modes = LinkedHashMap<String, Int>()
            while (raf.filePointer + CENTRAL_HEADER_FIXED_BYTES <= raf.length() &&
                readLittleInt(raf) == CENTRAL_SIGNATURE
            ) {
                val header = ByteArray(CENTRAL_HEADER_FIXED_BYTES - 4)
                raf.readFully(header)
                val versionMadeBy = littleShort(header, 0)
                val nameLength = littleShort(header, 24)
                val extraLength = littleShort(header, 26)
                val commentLength = littleShort(header, 28)
                val externalAttributes = littleInt(header, 34)
                val name = ByteArray(nameLength).also { raf.readFully(it) }.toString(Charsets.UTF_8)
                raf.skipBytes(extraLength + commentLength)
                // Non-UNIX creators (a Windows `zip`) store no usable mode here; the caller falls
                // back to the archive default rather than inventing an execute bit.
                val mode = if (versionMadeBy shr 8 == CREATOR_UNIX) {
                    externalAttributes ushr 16 and 0xFFFF
                } else {
                    null
                }
                if (mode != null) modes[name] = mode
            }
            return modes
        }
    }

    private fun findEndOfCentralDirectory(raf: RandomAccessFile): Long? {
        val length = raf.length()
        val searchFrom = maxOf(0L, length - MAX_EOCD_SEARCH)
        val buffer = ByteArray((length - searchFrom).toInt())
        raf.seek(searchFrom)
        raf.readFully(buffer)
        for (i in buffer.size - 22 downTo 0 step 1) {
            if (littleInt(buffer, i) == EOCD_SIGNATURE) return searchFrom + i
        }
        return null
    }

    private fun readLittleInt(raf: RandomAccessFile): Int {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return littleInt(bytes, 0)
    }

    private fun littleInt(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun littleShort(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
