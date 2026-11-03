package com.looker.droidify.network

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

class DownloadRetryPolicyTest {

    private val policy = DownloadRetryPolicy(
        maxRetries = 3,
        initialDelayMs = 500L,
        backoffMultiplier = 2.0,
        maxDelayMs = 4000L,
    )

    @Test
    fun `retries transient network errors within max retries`() {
        val socketTimeout = NetworkResponse.Error.SocketTimeout(SocketTimeoutException("timeout"))
        val connectionTimeout = NetworkResponse.Error.ConnectionTimeout(ConnectException("refused"))
        val ioError = NetworkResponse.Error.IO(IOException("reset"))
        val unknownError = NetworkResponse.Error.Unknown(RuntimeException("stream closed"))

        for (err in listOf(socketTimeout, connectionTimeout, ioError, unknownError)) {
            assertTrue(policy.shouldRetry(err, 0))
            assertTrue(policy.shouldRetry(err, 1))
            assertTrue(policy.shouldRetry(err, 2))
            assertFalse(policy.shouldRetry(err, 3), "Should not retry once maxRetries reached")
        }
    }

    @Test
    fun `retries 5xx server errors and specific retryable status codes`() {
        assertTrue(policy.shouldRetry(NetworkResponse.Error.Http(500), 0))
        assertTrue(policy.shouldRetry(NetworkResponse.Error.Http(502), 0))
        assertTrue(policy.shouldRetry(NetworkResponse.Error.Http(503), 0))
        assertTrue(policy.shouldRetry(NetworkResponse.Error.Http(504), 0))
        assertTrue(policy.shouldRetry(NetworkResponse.Error.Http(408), 0))
        assertTrue(policy.shouldRetry(NetworkResponse.Error.Http(429), 0))
    }

    @Test
    fun `does not retry client errors or success`() {
        assertFalse(policy.shouldRetry(NetworkResponse.Error.Http(404), 0))
        assertFalse(policy.shouldRetry(NetworkResponse.Error.Http(403), 0))
        assertFalse(policy.shouldRetry(NetworkResponse.Error.Http(401), 0))
        assertFalse(policy.shouldRetry(NetworkResponse.Error.Http(400), 0))
        assertFalse(policy.shouldRetry(NetworkResponse.Success(200, null, null), 0))
    }

    @Test
    fun `computes exponential backoff with upper limit`() {
        assertEquals(500L, policy.backoffDelay(0))
        assertEquals(1000L, policy.backoffDelay(1))
        assertEquals(2000L, policy.backoffDelay(2))
        assertEquals(4000L, policy.backoffDelay(3))
        assertEquals(4000L, policy.backoffDelay(4), "Should cap at maxDelayMs")
    }
}
