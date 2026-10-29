package com.diamondlauncher.core.nbt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Minimal, dependency-free Named Binary Tag (NBT) reader and writer ported from
 * `horizon-main/launcher/src/nbt.rs`.
 *
 * Used for:
 * - Reading world summaries from `saves/<world>/level.dat` (GZip-compressed root compound)
 * - Reading and writing the multiplayer server list in `servers.dat` (uncompressed root compound)
 */
sealed class NbtTag {
    data class ByteVal(val value: Byte) : NbtTag()
    data class ShortVal(val value: Short) : NbtTag()
    data class IntVal(val value: Int) : NbtTag()
    data class LongVal(val value: Long) : NbtTag()
    data class FloatVal(val value: Float) : NbtTag()
    data class DoubleVal(val value: Double) : NbtTag()
    data class ByteArrayVal(val value: ByteArray) : NbtTag()
    data class StringVal(val value: String) : NbtTag()
    data class ListVal(val elemType: Int, val items: List<NbtTag>) : NbtTag()
    data class CompoundVal(val entries: List<Pair<String, NbtTag>>) : NbtTag() {
        fun get(name: String): NbtTag? = entries.firstOrNull { it.first == name }?.second
        fun getString(name: String): String? = (get(name) as? StringVal)?.value
        fun getInt(name: String): Int? = when (val t = get(name)) {
            is IntVal -> t.value
            is ByteVal -> t.value.toInt()
            is ShortVal -> t.value.toInt()
            else -> null
        }
        fun getLong(name: String): Long? = (get(name) as? LongVal)?.value
        fun getBool(name: String): Boolean? = getInt(name)?.let { it != 0 }
        fun getCompound(name: String): CompoundVal? = get(name) as? CompoundVal
        fun getCompoundList(name: String): List<CompoundVal>? {
            val list = get(name) as? ListVal ?: return null
            return list.items.mapNotNull { it as? CompoundVal }
        }
    }
    data class IntArrayVal(val value: IntArray) : NbtTag()
    data class LongArrayVal(val value: LongArray) : NbtTag()

    val typeId: Int
        get() = when (this) {
            is ByteVal -> 1
            is ShortVal -> 2
            is IntVal -> 3
            is LongVal -> 4
            is FloatVal -> 5
            is DoubleVal -> 6
            is ByteArrayVal -> 7
            is StringVal -> 8
            is ListVal -> 9
            is CompoundVal -> 10
            is IntArrayVal -> 11
            is LongArrayVal -> 12
        }
}

object NbtCodec {

    fun parseFile(file: File): NbtTag.CompoundVal {
        val raw = file.readBytes()
        return parseBytes(raw)
    }

    fun parseBytes(raw: ByteArray): NbtTag.CompoundVal {
        val uncompressed = if (raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
        } else {
            raw
        }
        val dis = DataInputStream(ByteArrayInputStream(uncompressed))
        val rootType = dis.readUnsignedByte()
        require(rootType == 10) { "Root NBT tag is not TAG_Compound (got $rootType)" }
        readString(dis) // root name
        return readCompoundBody(dis)
    }

    fun encodeCompound(rootName: String, compound: NbtTag.CompoundVal, gzip: Boolean = false): ByteArray {
        val rawBaos = ByteArrayOutputStream()
        val dos = DataOutputStream(rawBaos)
        dos.writeByte(10)
        writeString(dos, rootName)
        writeCompoundBody(dos, compound)
        dos.flush()
        val rawBytes = rawBaos.toByteArray()
        if (!gzip) return rawBytes
        val gzBaos = ByteArrayOutputStream()
        GZIPOutputStream(gzBaos).use { it.write(rawBytes) }
        return gzBaos.toByteArray()
    }

    private fun readCompoundBody(dis: DataInputStream): NbtTag.CompoundVal {
        val fields = mutableListOf<Pair<String, NbtTag>>()
        while (true) {
            val tagType = dis.readUnsignedByte()
            if (tagType == 0) break
            val name = readString(dis)
            val payload = readPayload(dis, tagType)
            fields.add(name to payload)
        }
        return NbtTag.CompoundVal(fields)
    }

    private fun readPayload(dis: DataInputStream, tagType: Int): NbtTag = when (tagType) {
        1 -> NbtTag.ByteVal(dis.readByte())
        2 -> NbtTag.ShortVal(dis.readShort())
        3 -> NbtTag.IntVal(dis.readInt())
        4 -> NbtTag.LongVal(dis.readLong())
        5 -> NbtTag.FloatVal(dis.readFloat())
        6 -> NbtTag.DoubleVal(dis.readDouble())
        7 -> {
            val len = dis.readInt().coerceAtLeast(0)
            val arr = ByteArray(len)
            dis.readFully(arr)
            NbtTag.ByteArrayVal(arr)
        }
        8 -> NbtTag.StringVal(readString(dis))
        9 -> {
            val elemType = dis.readUnsignedByte()
            val len = dis.readInt().coerceAtLeast(0)
            val items = ArrayList<NbtTag>(len)
            repeat(len) {
                items.add(readPayload(dis, elemType))
            }
            NbtTag.ListVal(elemType, items)
        }
        10 -> readCompoundBody(dis)
        11 -> {
            val len = dis.readInt().coerceAtLeast(0)
            val arr = IntArray(len) { dis.readInt() }
            NbtTag.IntArrayVal(arr)
        }
        12 -> {
            val len = dis.readInt().coerceAtLeast(0)
            val arr = LongArray(len) { dis.readLong() }
            NbtTag.LongArrayVal(arr)
        }
        else -> throw IllegalArgumentException("Unknown NBT tag type: $tagType")
    }

    private fun writeCompoundBody(dos: DataOutputStream, compound: NbtTag.CompoundVal) {
        for ((name, tag) in compound.entries) {
            dos.writeByte(tag.typeId)
            writeString(dos, name)
            writePayload(dos, tag)
        }
        dos.writeByte(0)
    }

    private fun writePayload(dos: DataOutputStream, tag: NbtTag) {
        when (tag) {
            is NbtTag.ByteVal -> dos.writeByte(tag.value.toInt())
            is NbtTag.ShortVal -> dos.writeShort(tag.value.toInt())
            is NbtTag.IntVal -> dos.writeInt(tag.value)
            is NbtTag.LongVal -> dos.writeLong(tag.value)
            is NbtTag.FloatVal -> dos.writeFloat(tag.value)
            is NbtTag.DoubleVal -> dos.writeDouble(tag.value)
            is NbtTag.ByteArrayVal -> {
                dos.writeInt(tag.value.size)
                dos.write(tag.value)
            }
            is NbtTag.StringVal -> writeString(dos, tag.value)
            is NbtTag.ListVal -> {
                dos.writeByte(tag.elemType)
                dos.writeInt(tag.items.size)
                tag.items.forEach { writePayload(dos, it) }
            }
            is NbtTag.CompoundVal -> writeCompoundBody(dos, tag)
            is NbtTag.IntArrayVal -> {
                dos.writeInt(tag.value.size)
                tag.value.forEach { dos.writeInt(it) }
            }
            is NbtTag.LongArrayVal -> {
                dos.writeInt(tag.value.size)
                tag.value.forEach { dos.writeLong(it) }
            }
        }
    }

    private fun readString(dis: DataInputStream): String {
        val len = dis.readUnsignedShort()
        val buf = ByteArray(len)
        dis.readFully(buf)
        return String(buf, StandardCharsets.UTF_8)
    }

    private fun writeString(dos: DataOutputStream, s: String) {
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        dos.writeShort(bytes.size)
        dos.write(bytes)
    }
}