package com.looker.droidify.github

object ApkIconResolver {

    const val MAX_ARSC_SIZE = 48L * 1024 * 1024
    const val MAX_XML_SIZE = 2L * 1024 * 1024
    const val MAX_ICON_SIZE = 5L * 1024 * 1024

    data class ResolvedIcon(
        val path: String,
        val extension: String,
        val bytes: ByteArray,
    )

    suspend fun resolveIcon(
        reader: RangeReader,
        iconResId: Int,
        roundIconResId: Int = 0,
    ): ResolvedIcon? {
        val cd = ApkManifestReader.readCentralDirectory(reader)
        val arscEntry = ApkManifestReader.findEntry(cd, "resources.arsc") ?: return null
        if (arscEntry.uncompressedSize > MAX_ARSC_SIZE || arscEntry.compressedSize > MAX_ARSC_SIZE) {
            return null
        }
        val arscBytes = ApkManifestReader.readEntry(reader, arscEntry, MAX_ARSC_SIZE)
        val arsc = ArscParser(arscBytes)

        suspend fun findRaster(resId: Int): ResolvedIcon? {
            if (resId == 0) return null
            val candidates = arsc.resolveResId(resId)

            // Try non-.xml candidates from highest density down
            val nonXml = candidates
                .filterNot { it.path.endsWith(".xml", ignoreCase = true) }
                .sortedByDescending { ArscParser.rasterDensityScore(it.density) }

            for (candidate in nonXml) {
                val entry = ApkManifestReader.findEntry(cd, candidate.path) ?: continue
                if (entry.uncompressedSize > MAX_ICON_SIZE || entry.compressedSize > MAX_ICON_SIZE) continue
                val bytes = ApkManifestReader.readEntry(reader, entry, MAX_ICON_SIZE)
                val format = ContentSniffer.sniffRasterFormat(bytes)
                if (format != null) {
                    return ResolvedIcon(path = candidate.path, extension = format.extension, bytes = bytes)
                }
            }

            // If no raster found, check XML candidates (adaptive icon)
            val xmlCandidates = candidates.filter {
                it.path.endsWith(".xml", ignoreCase = true) || ArscParser.rasterDensityScore(it.density) < 0
            }
            for (xmlCandidate in xmlCandidates) {
                val xmlEntry = ApkManifestReader.findEntry(cd, xmlCandidate.path) ?: continue
                if (xmlEntry.uncompressedSize > MAX_XML_SIZE || xmlEntry.compressedSize > MAX_XML_SIZE) continue
                val xmlBytes = ApkManifestReader.readEntry(reader, xmlEntry, MAX_XML_SIZE)
                if (!ContentSniffer.isBinaryXml(xmlBytes)) continue

                when (val fg = AdaptiveIconXmlParser.parseForeground(xmlBytes)) {
                    is AdaptiveIconXmlParser.ForegroundResult.Reference -> {
                        if (fg.resId != resId) {
                            val fgCandidates = arsc.resolveResId(fg.resId)
                            val fgNonXml = fgCandidates
                                .filterNot { it.path.endsWith(".xml", ignoreCase = true) }
                                .sortedByDescending { ArscParser.rasterDensityScore(it.density) }

                            for (fgCandidate in fgNonXml) {
                                val fgEntry = ApkManifestReader.findEntry(cd, fgCandidate.path) ?: continue
                                if (fgEntry.uncompressedSize > MAX_ICON_SIZE || fgEntry.compressedSize > MAX_ICON_SIZE) continue
                                val bytes = ApkManifestReader.readEntry(reader, fgEntry, MAX_ICON_SIZE)
                                val format = ContentSniffer.sniffRasterFormat(bytes)
                                if (format != null) {
                                    return ResolvedIcon(path = fgCandidate.path, extension = format.extension, bytes = bytes)
                                }
                            }
                        }
                    }
                    is AdaptiveIconXmlParser.ForegroundResult.Path -> {
                        val fgEntry = ApkManifestReader.findEntry(cd, fg.path) ?: continue
                        if (fgEntry.uncompressedSize <= MAX_ICON_SIZE && fgEntry.compressedSize <= MAX_ICON_SIZE) {
                            val bytes = ApkManifestReader.readEntry(reader, fgEntry, MAX_ICON_SIZE)
                            val format = ContentSniffer.sniffRasterFormat(bytes)
                            if (format != null) {
                                return ResolvedIcon(path = fg.path, extension = format.extension, bytes = bytes)
                            }
                        }
                    }
                    null -> {}
                }
            }
            return null
        }

        return findRaster(iconResId)
            ?: (if (roundIconResId != 0 && roundIconResId != iconResId) findRaster(roundIconResId) else null)
    }

    suspend fun resolveIconPath(
        reader: RangeReader,
        iconResId: Int,
        roundIconResId: Int = 0,
    ): String? = resolveIcon(reader, iconResId, roundIconResId)?.path
}
