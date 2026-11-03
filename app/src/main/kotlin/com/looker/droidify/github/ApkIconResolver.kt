package com.looker.droidify.github

object ApkIconResolver {

    // Deflated resources.arsc has to be inflated as a whole, larger ones are not worth it
    const val MAX_DEFLATED_ARSC_SIZE = 4L * 1024 * 1024

    // Stored resources.arsc is read in blocks, give up after fetching this much
    const val MAX_ARSC_FETCH_SIZE = 4L * 1024 * 1024
    const val MAX_XML_SIZE = 2L * 1024 * 1024
    const val MAX_ICON_SIZE = 5L * 1024 * 1024

    data class ResolvedIcon(
        val path: String,
        val extension: String,
        val bytes: ByteArray,
        // Raster layer of an adaptive icon, which has a transparent safe zone around the logo
        val fromAdaptiveForeground: Boolean = false,
    )

    suspend fun resolveIcon(
        reader: RangeReader,
        iconResId: Int,
        roundIconResId: Int = 0,
        arscBlockSize: Int = BlockCachedRangeReader.DEFAULT_BLOCK_SIZE,
    ): ResolvedIcon? {
        val cd = ApkManifestReader.readCentralDirectory(reader)
        val arscEntry = ApkManifestReader.findEntry(cd, "resources.arsc") ?: return null
        val arsc = openArsc(reader, arscEntry, arscBlockSize) ?: return null

        // Giving up on a table which is too expensive to read means no icon
        suspend fun resolveResId(resId: Int): List<ArscParser.Candidate> = try {
            arsc.resolveResId(resId)
        } catch (_: FetchBudgetExceededException) {
            emptyList()
        }

        suspend fun findRaster(resId: Int): ResolvedIcon? {
            if (resId == 0) return null
            val candidates = resolveResId(resId)

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
                            val fgCandidates = resolveResId(fg.resId)
                            val fgNonXml = fgCandidates
                                .filterNot { it.path.endsWith(".xml", ignoreCase = true) }
                                .sortedByDescending { ArscParser.rasterDensityScore(it.density) }

                            for (fgCandidate in fgNonXml) {
                                val fgEntry = ApkManifestReader.findEntry(cd, fgCandidate.path) ?: continue
                                if (fgEntry.uncompressedSize > MAX_ICON_SIZE || fgEntry.compressedSize > MAX_ICON_SIZE) continue
                                val bytes = ApkManifestReader.readEntry(reader, fgEntry, MAX_ICON_SIZE)
                                val format = ContentSniffer.sniffRasterFormat(bytes)
                                if (format != null) {
                                    return ResolvedIcon(
                                        path = fgCandidate.path,
                                        extension = format.extension,
                                        bytes = bytes,
                                        fromAdaptiveForeground = true,
                                    )
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
                                return ResolvedIcon(
                                    path = fg.path,
                                    extension = format.extension,
                                    bytes = bytes,
                                    fromAdaptiveForeground = true,
                                )
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

    private suspend fun openArsc(
        reader: RangeReader,
        entry: ApkManifestReader.ZipEntry,
        blockSize: Int,
    ): ArscParser? = when (entry.method) {
        ApkManifestReader.METHOD_STORED -> {
            val dataOffset = ApkManifestReader.readDataOffset(reader, entry)
            if (dataOffset + entry.uncompressedSize > reader.size) {
                throw ApkProbeException("resources.arsc exceeds the file size")
            }
            try {
                ArscParser.create(
                    BlockCachedRangeReader(
                        reader = reader,
                        baseOffset = dataOffset,
                        size = entry.uncompressedSize,
                        maxFetchedBytes = MAX_ARSC_FETCH_SIZE,
                        blockSize = blockSize,
                    ),
                )
            } catch (_: FetchBudgetExceededException) {
                null
            }
        }
        ApkManifestReader.METHOD_DEFLATED -> {
            if (entry.uncompressedSize > MAX_DEFLATED_ARSC_SIZE || entry.compressedSize > MAX_DEFLATED_ARSC_SIZE) {
                null
            } else {
                ArscParser.create(
                    ByteArrayRangeReader(ApkManifestReader.readEntry(reader, entry, MAX_DEFLATED_ARSC_SIZE)),
                )
            }
        }
        else -> null
    }

    suspend fun resolveIconPath(
        reader: RangeReader,
        iconResId: Int,
        roundIconResId: Int = 0,
    ): String? = resolveIcon(reader, iconResId, roundIconResId)?.path
}
