package com.looker.droidify.github

object ApkIconResolver {

    const val MAX_ARSC_SIZE = 16L * 1024 * 1024
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
        val rasterPath = resolveRasterPath(reader, cd, iconResId, roundIconResId) ?: return null
        val iconEntry = ApkManifestReader.findEntry(cd, rasterPath) ?: return null
        if (iconEntry.uncompressedSize > MAX_ICON_SIZE || iconEntry.compressedSize > MAX_ICON_SIZE) {
            return null
        }
        val iconBytes = ApkManifestReader.readEntry(reader, iconEntry, MAX_ICON_SIZE)
        val ext = rasterPath.substringAfterLast('.', "png")
        return ResolvedIcon(path = rasterPath, extension = ext, bytes = iconBytes)
    }

    suspend fun resolveIconPath(
        reader: RangeReader,
        iconResId: Int,
        roundIconResId: Int = 0,
    ): String? {
        val cd = ApkManifestReader.readCentralDirectory(reader)
        return resolveRasterPath(reader, cd, iconResId, roundIconResId)
    }

    private suspend fun resolveRasterPath(
        reader: RangeReader,
        cd: ByteArray,
        iconResId: Int,
        roundIconResId: Int,
    ): String? {
        val arscEntry = ApkManifestReader.findEntry(cd, "resources.arsc") ?: return null
        if (arscEntry.uncompressedSize > MAX_ARSC_SIZE || arscEntry.compressedSize > MAX_ARSC_SIZE) {
            return null
        }
        val arscBytes = ApkManifestReader.readEntry(reader, arscEntry, MAX_ARSC_SIZE)
        val arsc = ArscParser(arscBytes)

        suspend fun findRaster(resId: Int): String? {
            if (resId == 0) return null
            val candidates = arsc.resolveResId(resId)
            val rasters = candidates.filter { ArscParser.isRaster(it.path) }
            if (rasters.isNotEmpty()) {
                return rasters.maxByOrNull { ArscParser.densityScore(it.density) }?.path
            }
            val xmlCandidate = candidates.firstOrNull { it.path.endsWith(".xml", ignoreCase = true) }
            if (xmlCandidate != null) {
                val xmlEntry = ApkManifestReader.findEntry(cd, xmlCandidate.path) ?: return null
                if (xmlEntry.uncompressedSize > MAX_XML_SIZE || xmlEntry.compressedSize > MAX_XML_SIZE) {
                    return null
                }
                val xmlBytes = ApkManifestReader.readEntry(reader, xmlEntry, MAX_XML_SIZE)
                when (val fg = AdaptiveIconXmlParser.parseForeground(xmlBytes)) {
                    is AdaptiveIconXmlParser.ForegroundResult.Reference -> {
                        if (fg.resId != resId) {
                            val fgCandidates = arsc.resolveResId(fg.resId)
                            val fgRasters = fgCandidates.filter { ArscParser.isRaster(it.path) }
                            if (fgRasters.isNotEmpty()) {
                                return fgRasters.maxByOrNull { ArscParser.densityScore(it.density) }?.path
                            }
                        }
                    }
                    is AdaptiveIconXmlParser.ForegroundResult.Path -> {
                        if (ArscParser.isRaster(fg.path)) {
                            return fg.path
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
}
