/*
 * Copyright (c) 2025, 贵州君城网络科技有限公司
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.combo.aar2apk.internal.utils

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

internal object DexClassNamesReader {

    private val DEX_ENTRY_REGEX = Regex("classes\\d*\\.dex")

    fun readClassNames(apkFile: File): List<String> {
        val names = mutableListOf<String>()
        ZipFile(apkFile).use { zip ->
            for (entry in zip.entries()) {
                if (!DEX_ENTRY_REGEX.matches(entry.name)) continue
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                names.addAll(parseDex(bytes))
            }
        }
        return names
    }

    private fun parseDex(bytes: ByteArray): List<String> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val stringIdsOff = buffer.getInt(0x3C)
        val typeIdsOff = buffer.getInt(0x44)
        val classDefsSize = buffer.getInt(0x60)
        val classDefsOff = buffer.getInt(0x64)
        val result = ArrayList<String>(classDefsSize)
        for (index in 0 until classDefsSize) {
            val classIdx = buffer.getInt(classDefsOff + index * 32)
            val descriptorIdx = buffer.getInt(typeIdsOff + classIdx * 4)
            val stringOff = buffer.getInt(stringIdsOff + descriptorIdx * 4)
            val descriptor = readStringData(bytes, stringOff)
            if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                result.add(descriptor.substring(1, descriptor.length - 1).replace('/', '.'))
            }
        }
        return result
    }

    private fun readStringData(bytes: ByteArray, offset: Int): String {
        var pos = offset
        while (bytes[pos].toInt() and 0x80 != 0) {
            pos++
        }
        pos++
        val start = pos
        while (bytes[pos].toInt() != 0) {
            pos++
        }
        return String(bytes, start, pos - start, Charsets.UTF_8)
    }
}
