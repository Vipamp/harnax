package com.agnetix.harnax.auth

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

class AuthRestTemplateInterceptor(
    private val tokenProvider: InternalTokenProvider,
    private val scope: String,
) : ClientHttpRequestInterceptor {

    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        val headers = tokenProvider.authHeaders(scope)
        headers.forEach { (key, value) -> request.headers.set(key, value) }
        return execution.execute(request, body)
    }
}
