/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp.android.test

import android.os.Build
import android.support.test.runner.AndroidJUnit4
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Call
import okhttp3.CertificatePinner
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.RecordingEventListener
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.internal.platform.Platform
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.internal.TlsUtil.localhost
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.UnknownHostException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket

/**
 * Run with "./gradlew :android-test:connectedCheck" and make sure ANDROID_SDK_ROOT is set.
 */
@RunWith(AndroidJUnit4::class)
class OkHttpTest {
  private lateinit var client: OkHttpClient

  private val moshi = Moshi.Builder()
      .add(KotlinJsonAdapterFactory())
      .build()

  @JvmField
  @Rule
  val server = MockWebServer()
  private val handshakeCertificates = localhost()

  @Before
  fun createClient() {
    client = OkHttpClient.Builder()
        .build()
  }

  @After
  fun cleanup() {
    client.dispatcher.executorService.shutdownNow()
  }

  @Test
  fun testRequest() {
    assumeNetwork()

    val request = Request.Builder().url("https://api.twitter.com/robots.txt").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testRequestUsesAndroidConscrypt() {
    assumeNetwork()

    val request = Request.Builder().url("https://facebook.com/robots.txt").build()

    var socketClass: String? = null

    val client2 = client.newBuilder()
        .eventListener(object : EventListener() {
          override fun connectionAcquired(call: Call, connection: Connection) {
            socketClass = connection.socket().javaClass.name
          }
        })
        .build()

    val response = client2.newCall(request).execute()

    response.use {
      assertEquals(Protocol.HTTP_2, response.protocol)
      if (Build.VERSION.SDK_INT >= 29) {
        assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
      } else {
        assertEquals(TlsVersion.TLS_1_2, response.handshake?.tlsVersion)
      }
      assertEquals(200, response.code)
      assertTrue(socketClass?.startsWith("com.android.org.conscrypt.") == true)
    }
  }

  @Test
  fun testHttpRequestNotBlockedOnLegacyAndroid() {
    assumeTrue(Build.VERSION.SDK_INT < 23)

    val request = Request.Builder().url("http://squareup.com/robots.txt").build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testHttpRequestBlocked() {
    assumeTrue(Build.VERSION.SDK_INT >= 23)

    val request = Request.Builder().url("http://squareup.com/robots.txt").build()

    try {
      client.newCall(request).execute()
      fail("expected cleartext blocking")
    } catch (_: java.net.UnknownServiceException) {
    }
  }

  data class HowsMySslResults(
    val unknown_cipher_suite_supported: Boolean,
    val beast_vuln: Boolean,
    val session_ticket_supported: Boolean,
    val tls_compression_supported: Boolean,
    val ephemeral_keys_supported: Boolean,
    val rating: String,
    val tls_version: String,
    val able_to_detect_n_minus_one_splitting: Boolean,
    val insecure_cipher_suites: Map<String, List<String>>,
    val given_cipher_suites: List<String>?
  )

  @Test
  @Ignore
  fun testSSLFeatures() {
    assumeNetwork()

    val request = Request.Builder().url("https://www.howsmyssl.com/a/check").build()

    val response = client.newCall(request).execute()

    val results = response.use {
      moshi.adapter(HowsMySslResults::class.java).fromJson(response.body!!.string())!!
    }

    Platform.get().log(Platform.WARN, "results $results", null)

    assertTrue(results.session_ticket_supported)
    assertEquals("Probably Okay", results.rating)
    // TODO map to expected versions automatically, test ignored for now.  Run manually.
    assertEquals("TLS 1.3", results.tls_version)
    assertEquals(0, results.insecure_cipher_suites.size)

    assertEquals(TlsVersion.TLS_1_3, response.handshake?.tlsVersion)
    assertEquals(Protocol.HTTP_2, response.protocol)
  }

  @Test
  fun testMockWebserverRequest() {
    enableTls()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testCertificatePinningFailure() {
    enableTls()

    val certificatePinner = CertificatePinner.Builder()
        .add(server.hostName, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        .build()
    client = client.newBuilder().certificatePinner(certificatePinner).build()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    try {
      client.newCall(request).execute()
      fail()
    } catch (_: SSLPeerUnverifiedException) {
    }
  }

  @Test
  fun testCertificatePinningSuccess() {
    enableTls()

    val certificatePinner = CertificatePinner.Builder()
        .add(server.hostName,
            CertificatePinner.pin(handshakeCertificates.trustManager.acceptedIssuers[0]))
        .build()
    client = client.newBuilder().certificatePinner(certificatePinner).build()

    server.enqueue(MockResponse().setBody("abc"))

    val request = Request.Builder().url(server.url("/")).build()

    val response = client.newCall(request).execute()

    response.use {
      assertEquals(200, response.code)
    }
  }

  @Test
  fun testEventListener() {
    val eventListener = RecordingEventListener()

    client = client.newBuilder().eventListener(eventListener).build()

    enableTls()

    server.enqueue(MockResponse().setBody("abc1"))
    server.enqueue(MockResponse().setBody("abc2"))

    val request = Request.Builder().url(server.url("/")).build()

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(listOf("CallStart", "ProxySelectStart", "ProxySelectEnd", "DnsStart", "DnsEnd",
        "ConnectStart", "SecureConnectStart", "SecureConnectEnd", "ConnectEnd",
        "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd"), eventListener.recordedEventTypes())

    eventListener.clearAllEvents()

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(listOf("CallStart", "ProxySelectStart", "ProxySelectEnd",
        "ConnectionAcquired", "RequestHeadersStart", "RequestHeadersEnd", "ResponseHeadersStart",
        "ResponseHeadersEnd", "ResponseBodyStart", "ResponseBodyEnd", "ConnectionReleased",
        "CallEnd"), eventListener.recordedEventTypes())
  }

  @Test
  fun testSessionReuse() {
    val sessionIds = mutableListOf<String>()

    client = client.newBuilder().eventListener(object : EventListener() {
      override fun connectionAcquired(call: Call, connection: Connection) {
        val sslSocket = connection.socket() as SSLSocket

        sessionIds.add(sslSocket.session.id.toByteString().hex())
      }
    }).build()

    enableTls()

    server.enqueue(MockResponse().setBody("abc1"))
    server.enqueue(MockResponse().setBody("abc2"))

    val request = Request.Builder().url(server.url("/")).build()

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    client.connectionPool.evictAll()
    assertEquals(0, client.connectionPool.connectionCount())

    client.newCall(request).execute().use { response ->
      assertEquals(200, response.code)
    }

    assertEquals(2, sessionIds.size)
    assertEquals(sessionIds[0], sessionIds[1])
  }

  private fun enableTls() {
    client = client.newBuilder()
        .sslSocketFactory(
            handshakeCertificates.sslSocketFactory(), handshakeCertificates.trustManager)
        .build()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
  }

  private fun assumeNetwork() {
    try {
      InetAddress.getByName("www.google.com")
    } catch (uhe: UnknownHostException) {
      assumeNoException(uhe)
    }
  }
}
