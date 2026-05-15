package com.scoova.monitor.sdk

/**
 * OkHttp Interceptor for automatic network performance tracking (Fix #10).
 *
 * Usage:
 * ```kotlin
 * val client = OkHttpClient.Builder()
 *     .addInterceptor(ScoovaOkHttpInterceptor())
 *     .build()
 * ```
 *
 * This class uses reflection to avoid a hard dependency on OkHttp.
 * If OkHttp is not in the classpath, this class simply won't be used.
 */
class ScoovaOkHttpInterceptor : Any() {

    // Using reflection to implement okhttp3.Interceptor without compile dependency
    companion object {
        /**
         * Create an OkHttp Interceptor instance via reflection.
         * Returns null if OkHttp is not available.
         */
        fun createIfAvailable(): Any? {
            return try {
                val interceptorClass = Class.forName("okhttp3.Interceptor")
                val chainClass = Class.forName("okhttp3.Interceptor\$Chain")

                // Create a dynamic proxy that implements okhttp3.Interceptor
                java.lang.reflect.Proxy.newProxyInstance(
                    interceptorClass.classLoader,
                    arrayOf(interceptorClass)
                ) { _, method, args ->
                    if (method.name == "intercept" && args != null && args.isNotEmpty()) {
                        interceptOkHttp(args[0])
                    } else {
                        null
                    }
                }
            } catch (_: ClassNotFoundException) {
                null // OkHttp not available
            } catch (_: Exception) {
                null
            }
        }

        private fun interceptOkHttp(chain: Any): Any? {
            return try {
                val chainClass = chain.javaClass
                val requestMethod = chainClass.getMethod("request")
                val proceedMethod = chainClass.getMethod("proceed", Class.forName("okhttp3.Request"))

                val request = requestMethod.invoke(chain)
                val requestClass = request.javaClass

                val urlMethod = requestClass.getMethod("url")
                val methodMethod = requestClass.getMethod("method")

                val url = urlMethod.invoke(request).toString()
                val httpMethod = methodMethod.invoke(request) as String

                // Skip our own requests
                if (url.contains("scoo-va.info")) {
                    return proceedMethod.invoke(chain, request)
                }

                val startTime = System.currentTimeMillis()
                val response = proceedMethod.invoke(chain, request)
                val duration = System.currentTimeMillis() - startTime

                val responseClass = response!!.javaClass
                val codeMethod = responseClass.getMethod("code")
                val statusCode = codeMethod.invoke(response) as Int

                // Track automatically
                if (ScoovaMonitor.isInitialized) {
                    ScoovaMonitor.trackNetworkRequest(url, httpMethod, statusCode, duration)
                }

                response
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException // Re-throw the actual exception
            } catch (e: Exception) {
                // If tracking fails, still let the request proceed
                try {
                    val chainClass = chain.javaClass
                    val requestMethod = chainClass.getMethod("request")
                    val proceedMethod = chainClass.getMethod("proceed", Class.forName("okhttp3.Request"))
                    proceedMethod.invoke(chain, requestMethod.invoke(chain))
                } catch (e2: java.lang.reflect.InvocationTargetException) {
                    throw e2.targetException
                }
            }
        }
    }
}
