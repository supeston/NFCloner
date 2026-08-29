package com.mifare.cloner.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import android.nfc.tech.NfcA
import com.mifare.cloner.data.MifareDump
import com.mifare.cloner.data.ScanMode
import java.io.IOException

sealed class ReadResult {
    data class Success(val dump: MifareDump) : ReadResult()
    data class Failure(val message: String) : ReadResult()
}

sealed class WriteResult {
    data class Success(val dump: MifareDump) : WriteResult()
    data class Failure(val message: String) : WriteResult()
}

sealed class WipeResult {
    object Success : WipeResult()
    data class Failure(val message: String) : WipeResult()
}

object MifareGen2Cloner {

    val KNOWN_KEYS_PRESETS = listOf(
        "FFFFFFFFFFFF",
        "000000000000",
        "A0A1A2A3A4A5",
        "B0B1B2B3B4B5",
        "D3F7D3F7D3F7",
        "A0B0C0D0E0F0",
        "A1B2C3D4E5F6",
        "4D3A99C351DD",
        "1A982C7E459A",
        // Transport keys for dumping
        "5A362948710E", "48710E5A3629", "710E5A362948", "2948710E5A36", "362948710E5A", "0E5A36294871",
        "534341535031", "435341535031",
        "A73F5DC1D333", "1A9C26E3A8B5", "E35173494A81",
        "112233445566", "223344556677"
    )

    fun readOriginalTag(
        tag: Tag,
        scanMode: ScanMode,
        customSector: Int,
        keysList: List<String>,
        checkTransportSectors: Boolean = false
    ): ReadResult {
        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            return ReadResult.Failure("Метка не является MIFARE Classic (возможно, NTAG или Ultralight)")
        }

        try {
            mifare.connect()
            mifare.timeout = 1500

            val uidHex = MifareDump.bytesToHex(tag.id)
            val nfca = NfcA.get(tag)
            val atqa = if (nfca != null) MifareDump.bytesToHex(nfca.atqa) else "0004"
            val sak = nfca?.sak?.toInt() ?: mifare.type

            val allKeysStrings = (keysList + KNOWN_KEYS_PRESETS).distinct()
            val binaryKeys = allKeysStrings.mapNotNull {
                try {
                    if (it.length == 12) MifareDump.hexToBytes(it) else null
                } catch (_: Exception) {
                    null
                }
            }.ifEmpty {
                listOf(MifareClassic.KEY_DEFAULT)
            }

            val blocksMap = HashMap<Int, String>()
            val sectorKeysMap = HashMap<Int, String>()
            var primaryKeyUsedHex = "FFFFFFFFFFFF"

            val baseSectors = when (scanMode) {
                ScanMode.SECTOR_0 -> listOf(0)
                ScanMode.CUSTOM_SECTOR -> listOf(customSector.coerceIn(0, mifare.sectorCount - 1))
                ScanMode.FULL_DUMP -> (0 until mifare.sectorCount).toList()
            }

            val sectorsToRead = if (checkTransportSectors && scanMode != ScanMode.FULL_DUMP) {
                (baseSectors + listOf(4, 7, 8, 11)).filter { it < mifare.sectorCount }.distinct().sorted()
            } else {
                baseSectors
            }

            for (sector in sectorsToRead) {
                var authenticated = false
                var sectorKeyBytes = MifareClassic.KEY_DEFAULT

                for (keyBytes in binaryKeys) {
                    if (mifare.authenticateSectorWithKeyA(sector, keyBytes)) {
                        authenticated = true
                        sectorKeyBytes = keyBytes
                        val kHex = MifareDump.bytesToHex(keyBytes)
                        sectorKeysMap[sector] = kHex
                        if (sector == 0) primaryKeyUsedHex = kHex
                        break
                    } else if (mifare.authenticateSectorWithKeyB(sector, keyBytes)) {
                        authenticated = true
                        sectorKeyBytes = keyBytes
                        val kHex = MifareDump.bytesToHex(keyBytes)
                        sectorKeysMap[sector] = kHex
                        if (sector == 0) primaryKeyUsedHex = kHex
                        break
                    }
                }

                if (!authenticated) {
                    if (scanMode == ScanMode.SECTOR_0 && sector == 0) {
                        return ReadResult.Failure("Не удалось подобрать ключ для Сектора 0. Добавьте нужный ключ в Настройках.")
                    } else if (scanMode == ScanMode.CUSTOM_SECTOR && sector == customSector) {
                        return ReadResult.Failure("Не удалось подобрать ключ для Сектора $customSector")
                    } else {
                        continue
                    }
                }

                val firstBlock = mifare.sectorToBlock(sector)
                val blockCount = mifare.getBlockCountInSector(sector)
                val trailerBlockIndex = firstBlock + blockCount - 1

                for (b in 0 until blockCount) {
                    val blockIndex = firstBlock + b
                    try {
                        val rawBytes = mifare.readBlock(blockIndex)

                        if (blockIndex == trailerBlockIndex) {
                            // Chip hardware masks Key A (bytes 0..5) with zeros on read.
                            // We construct the genuine trailer with the authenticated key and standard access bits!
                            val restoredTrailer = ByteArray(16)
                            System.arraycopy(rawBytes, 0, restoredTrailer, 0, 16)

                            // 1. Restore Key A (bytes 0..5)
                            System.arraycopy(sectorKeyBytes, 0, restoredTrailer, 0, 6)

                            // 2. Validate Access Bits (bytes 6..9)
                            val isAccessZero = (6..9).all { restoredTrailer[it] == 0.toByte() }
                            if (isAccessZero) {
                                restoredTrailer[6] = 0xFF.toByte()
                                restoredTrailer[7] = 0x07.toByte()
                                restoredTrailer[8] = 0x80.toByte()
                                restoredTrailer[9] = 0x69.toByte()
                            }

                            // 3. Validate Key B (bytes 10..15)
                            val isKeyBZero = (10..15).all { restoredTrailer[it] == 0.toByte() }
                            if (isKeyBZero) {
                                System.arraycopy(sectorKeyBytes, 0, restoredTrailer, 10, 6)
                            }

                            blocksMap[blockIndex] = MifareDump.bytesToHex(restoredTrailer)
                        } else {
                            blocksMap[blockIndex] = MifareDump.bytesToHex(rawBytes)
                        }
                    } catch (_: Exception) {
                        // Trailer block or permission read error
                    }
                }
            }

            if (blocksMap.isEmpty()) {
                return ReadResult.Failure("Не удалось прочитать ни одного блока данных")
            }

            val timestamp = System.currentTimeMillis()
            val defaultName = "UID_${uidHex.uppercase()}"

            val dump = MifareDump(
                name = defaultName,
                uidHex = uidHex,
                sak = sak,
                atqa = atqa,
                timestamp = timestamp,
                scanMode = scanMode,
                targetSector = customSector,
                blocks = blocksMap,
                keyUsed = primaryKeyUsedHex,
                sectorKeys = sectorKeysMap
            )

            return ReadResult.Success(dump)

        } catch (e: TagLostException) {
            return ReadResult.Failure("Метка смещена слишком быстро, повторите")
        } catch (e: IOException) {
            return ReadResult.Failure("Ошибка ввода/вывода NFC: ${e.localizedMessage ?: "потеря связи"}")
        } catch (e: Exception) {
            return ReadResult.Failure("Ошибка чтения: ${e.localizedMessage ?: "неизвестная ошибка"}")
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {}
        }
    }

    fun writeToGen2Tag(
        tag: Tag,
        dump: MifareDump,
        keysList: List<String>
    ): WriteResult {
        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            return WriteResult.Failure("Заготовка не является MIFARE Classic (нужна Gen 2 CUID)")
        }

        try {
            mifare.connect()
            mifare.timeout = 2000

            // Combine all user keys, dump keys, and recovery keys (including 000000000000 and FFFFFFFFFFFF)
            val allKeysStrings = (
                listOf(dump.keyUsed) +
                dump.sectorKeys.values +
                keysList +
                KNOWN_KEYS_PRESETS
            ).distinct()

            val binaryKeys = allKeysStrings.mapNotNull {
                try {
                    if (it.length == 12) MifareDump.hexToBytes(it) else null
                } catch (_: Exception) {
                    null
                }
            }.toMutableList()

            if (!binaryKeys.any { it.contentEquals(MifareClassic.KEY_DEFAULT) }) {
                binaryKeys.add(0, MifareClassic.KEY_DEFAULT)
            }

            // Step 1: Authenticate Sector 0 on the Gen 2 card
            var authSector0 = false
            for (keyBytes in binaryKeys) {
                if (mifare.authenticateSectorWithKeyA(0, keyBytes) || mifare.authenticateSectorWithKeyB(0, keyBytes)) {
                    authSector0 = true
                    break
                }
            }

            if (!authSector0) {
                return WriteResult.Failure("Не удалось авторизовать Сектор 0 на заготовке (ключ заготовки неизвестен)")
            }

            // Step 2: Write Block 0 (UID / Manufacturer block) - CUID Gen 2 Direct Write
            val block0Bytes = dump.getBlockBytes(0)
            if (block0Bytes != null) {
                try {
                    mifare.writeBlock(0, block0Bytes)
                } catch (e: Exception) {
                    return WriteResult.Failure("Заготовка отклонила запись в Блок 0. Убедитесь, что используете заготовку Gen 2 (CUID).")
                }

                // Verify Block 0
                val read0 = mifare.readBlock(0)
                if (!read0.contentEquals(block0Bytes)) {
                    return WriteResult.Failure("Верификация Блока 0 не прошла: данные не совпали. Метка не является перезаписываемой Gen 2.")
                }
            }

            // Step 3: Write remaining blocks & correctly rewrite Sector Trailer blocks
            val targetSectors = when (dump.scanMode) {
                ScanMode.SECTOR_0 -> listOf(0)
                ScanMode.CUSTOM_SECTOR -> listOf(dump.targetSector)
                ScanMode.FULL_DUMP -> (0 until mifare.sectorCount).toList()
            }

            for (sector in targetSectors) {
                var sectorAuthed = false
                for (keyBytes in binaryKeys) {
                    if (mifare.authenticateSectorWithKeyA(sector, keyBytes) || mifare.authenticateSectorWithKeyB(sector, keyBytes)) {
                        sectorAuthed = true
                        break
                    }
                }

                if (!sectorAuthed) continue

                val firstBlock = mifare.sectorToBlock(sector)
                val blockCount = mifare.getBlockCountInSector(sector)
                val trailerBlockIndex = firstBlock + blockCount - 1

                // 3a. Write Data Blocks in Sector
                for (b in 0 until (blockCount - 1)) {
                    val blockIndex = firstBlock + b
                    if (blockIndex == 0) continue // already written

                    val dataBytes = dump.getBlockBytes(blockIndex)
                    if (dataBytes != null) {
                        try {
                            mifare.writeBlock(blockIndex, dataBytes)
                        } catch (_: Exception) {}
                    }
                }

                // 3b. Write Trailer Block with authentic original Key A + Standard Transport Access Bits + Key B
                val originalKeyHex = dump.sectorKeys[sector] ?: dump.keyUsed
                val originalKeyBytes = try {
                    MifareDump.hexToBytes(originalKeyHex)
                } catch (_: Exception) {
                    MifareClassic.KEY_DEFAULT
                }

                val trailerBytes = ByteArray(16)
                // Key A (bytes 0..5): original authentic key (e.g. FFFFFFFFFFFF)
                System.arraycopy(originalKeyBytes, 0, trailerBytes, 0, 6)

                // Access Bits (bytes 6..9): Standard Transport Access Bits (FF 07 80 69)
                // Grants full read/write access to Key A for all data blocks and trailer
                trailerBytes[6] = 0xFF.toByte()
                trailerBytes[7] = 0x07.toByte()
                trailerBytes[8] = 0x80.toByte()
                trailerBytes[9] = 0x69.toByte()

                // Key B (bytes 10..15): original authentic key (e.g. FFFFFFFFFFFF)
                System.arraycopy(originalKeyBytes, 0, trailerBytes, 10, 6)

                try {
                    mifare.writeBlock(trailerBlockIndex, trailerBytes)
                } catch (_: Exception) {}
            }

            return WriteResult.Success(dump)

        } catch (e: TagLostException) {
            return WriteResult.Failure("Метка смещена во время записи! Повторите операцию.")
        } catch (e: IOException) {
            return WriteResult.Failure("Ошибка NFC при записи: ${e.localizedMessage}")
        } catch (e: Exception) {
            return WriteResult.Failure("Ошибка записи: ${e.localizedMessage}")
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {}
        }
    }

    fun wipeGen2Tag(
        tag: Tag,
        keysList: List<String>
    ): WipeResult {
        val mifare = MifareClassic.get(tag)
        if (mifare == null) {
            return WipeResult.Failure("Метка не является MIFARE Classic")
        }

        try {
            mifare.connect()
            mifare.timeout = 2000

            val allKeysStrings = (keysList + KNOWN_KEYS_PRESETS).distinct()
            val binaryKeys = allKeysStrings.mapNotNull {
                try {
                    if (it.length == 12) MifareDump.hexToBytes(it) else null
                } catch (_: Exception) {
                    null
                }
            }

            val zeroData = ByteArray(16)
            val defaultTrailer = ByteArray(16) { 0xFF.toByte() }.apply {
                this[6] = 0xFF.toByte()
                this[7] = 0x07.toByte()
                this[8] = 0x80.toByte()
                this[9] = 0x69.toByte()
            }

            var wipedSectorsCount = 0

            for (sector in 0 until mifare.sectorCount) {
                var authenticated = false
                for (keyBytes in binaryKeys) {
                    if (mifare.authenticateSectorWithKeyA(sector, keyBytes) || mifare.authenticateSectorWithKeyB(sector, keyBytes)) {
                        authenticated = true
                        break
                    }
                }

                if (!authenticated) continue

                val firstBlock = mifare.sectorToBlock(sector)
                val blockCount = mifare.getBlockCountInSector(sector)
                val trailerBlock = firstBlock + blockCount - 1

                for (b in 0 until (blockCount - 1)) {
                    val blockIndex = firstBlock + b
                    if (blockIndex == 0) continue // Keep UID on wipe
                    try {
                        mifare.writeBlock(blockIndex, zeroData)
                    } catch (_: Exception) {}
                }

                try {
                    mifare.writeBlock(trailerBlock, defaultTrailer)
                    wipedSectorsCount++
                } catch (_: Exception) {}
            }

            return if (wipedSectorsCount > 0) WipeResult.Success else WipeResult.Failure("Не удалось авторизовать ни одного сектора для очистки")

        } catch (e: Exception) {
            return WipeResult.Failure("Ошибка очистки: ${e.localizedMessage}")
        } finally {
            try {
                mifare.close()
            } catch (_: Exception) {}
        }
    }
}
