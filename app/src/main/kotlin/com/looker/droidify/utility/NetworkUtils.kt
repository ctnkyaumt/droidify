package com.looker.droidify.utility

fun extractHost(addressOrHost: String): String {
    val trimmed = addressOrHost.trim()
    val hostPart = if (trimmed.contains("://")) {
        trimmed.substringAfter("://").substringBefore('/')
    } else {
        trimmed.substringBefore('/')
    }
    val host = if (hostPart.startsWith("[")) {
        hostPart.substringBefore(']').removePrefix("[")
    } else if (hostPart.count { it == ':' } == 1) {
        hostPart.substringBefore(':')
    } else {
        hostPart
    }
    return host.trim().lowercase()
}

fun isLocalHost(addressOrHost: String): Boolean {
    val host = extractHost(addressOrHost)
    if (host.isEmpty()) return false

    if (host == "localhost") return true
    if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa")) return true

    // IPv4 check
    val ipv4Parts = host.split('.')
    if (ipv4Parts.size == 4) {
        val bytes = ipv4Parts.mapNotNull { it.toIntOrNull() }
        if (bytes.size == 4 && bytes.all { it in 0..255 }) {
            val (b0, b1) = bytes[0] to bytes[1]
            return when {
                b0 == 10 -> true
                b0 == 127 -> true
                b0 == 172 && b1 in 16..31 -> true
                b0 == 192 && b1 == 168 -> true
                b0 == 169 && b1 == 254 -> true
                else -> false
            }
        }
    }

    // Single-label hostname (no dots, not IPv6)
    if (!host.contains('.') && !host.contains(':')) {
        return true
    }

    // IPv6 checks
    if (host.contains(':')) {
        if (host == "::1" || host == "0:0:0:0:0:0:0:1") return true
        if (host.startsWith("fc") || host.startsWith("fd")) return true
        val p4 = host.take(4)
        if (p4.startsWith("fe8") || p4.startsWith("fe9") || p4.startsWith("fea") || p4.startsWith("feb")) {
            return true
        }
        return false
    }

    return false
}
