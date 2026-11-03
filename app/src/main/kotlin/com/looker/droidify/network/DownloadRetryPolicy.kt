package com.looker.droidify.network

data class DownloadRetryPolicy(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000L,
    val backoffMultiplier: Double = 2.0,
    val maxDelayMs: Long = 8000L,
) {
    fun shouldRetry(response: NetworkResponse, attempt: Int): Boolean {
        if (attempt >= maxRetries) return false
        return when (response) {
            is NetworkResponse.Error.ConnectionTimeout,
            is NetworkResponse.Error.SocketTimeout,
            is NetworkResponse.Error.IO -> true

            is NetworkResponse.Error.Http -> {
                // 416: the partial file was deleted, so the next attempt starts from zero
                response.statusCode == 408 || response.statusCode == 416 || response.statusCode == 429 || response.statusCode in 500..599
            }

            is NetworkResponse.Error.Unknown -> true
            is NetworkResponse.Success -> false
        }
    }

    fun backoffDelay(attempt: Int): Long {
        var delay = initialDelayMs
        repeat(attempt) {
            delay = (delay * backoffMultiplier).toLong()
        }
        return delay.coerceAtMost(maxDelayMs)
    }
}
