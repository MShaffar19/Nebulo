package com.frostnerd.smokescreen.util.speedtest

import androidx.annotation.IntRange
import com.frostnerd.dnstunnelproxy.DnsServerInformation
import com.frostnerd.dnstunnelproxy.UpstreamAddress
import com.frostnerd.encrypteddnstunnelproxy.*
import com.frostnerd.encrypteddnstunnelproxy.tls.TLSUpstreamAddress
import okhttp3.*
import org.minidns.dnsmessage.DnsMessage
import org.minidns.dnsmessage.Question
import org.minidns.record.Record
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.Exception
import java.net.*
import java.time.Duration
import java.time.temporal.TemporalUnit
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/*
 * Copyright (C) 2019 Daniel Wolf (Ch4t4r)
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * You can contact the developer at daniel.wolf@frostnerd.com.
 */

class DnsSpeedTest(val server: DnsServerInformation<*>, val connectTimeout: Int = 2500, val readTimeout:Int = 1500) {
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .dns(httpsDnsClient)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
            .build()
    }
    private val httpsDnsClient by lazy {
        PinnedDns((server as HttpsDnsServerInformation).serverConfigurations.values.map {
            it.urlCreator.address
        })
    }
    companion object {
        val testDomains = listOf("google.com", "frostnerd.com", "amazon.com", "youtube.com", "github.com",
            "stackoverflow.com", "stackexchange.com", "spotify.com", "material.io", "reddit.com", "android.com")
    }

    /**
     * @param passes The amount of requests to make
     * @return The average response time (in ms)
     */
    fun runTest(@IntRange(from = 1) passes: Int): Int? {
        println("Running test for ${server.name}")
        var ttl = 0
        for (i in 0 until passes) {
            if (server is HttpsDnsServerInformation) {
                server.serverConfigurations.values.forEach {
                    ttl += testHttps(it) ?: 0
                }
            } else {
                (server as DnsServerInformation<TLSUpstreamAddress>).servers.forEach {
                    ttl += testTls(it.address) ?: 0
                }
            }
        }
        return (ttl / passes).let {
            if (it <= 0) null else it
        }
    }

    private fun testHttps(config: ServerConfiguration): Int? {
        val msg = createTestDnsPacket()
        val url: URL = config.urlCreator.createUrl(msg, config.urlCreator.address)
        val requestBuilder = Request.Builder().url(url)
        if (config.requestHasBody) {
            val body = config.bodyCreator!!.createBody(msg, config.urlCreator.address)
            if (body != null) {
                requestBuilder.header("Content-Type", config.contentType)
                requestBuilder.post(RequestBody.create(body.mediaType, body.rawBody))
            } else {
                return null
            }
        }
        var response:Response? = null
        try {
            val start = System.currentTimeMillis()
            response = httpClient.newCall(requestBuilder.build()).execute()
            if(!response.isSuccessful) return null
            val body = response.body() ?: return null
            val bytes = body.bytes()
            val time = (System.currentTimeMillis() - start).toInt()

            if (bytes.size < 17) {
                return null
            } else if(!testResponse(DnsMessage(bytes))) {
                return null
            }
            return time
        } catch (ex: Exception) {
            return null
        } finally {
            if(response?.body() != null) response.close()
        }
    }

    private fun testTls(address: TLSUpstreamAddress): Int? {
        val addr =
            address.addressCreator.resolveOrGetResultOrNull(retryIfError = true, runResolveNow = true) ?: return null
        var socket: Socket? = null
        try {
            socket = SSLSocketFactory.getDefault().createSocket()
            val msg = createTestDnsPacket()
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(addr[0], address.port), connectTimeout)
            socket.soTimeout = readTimeout
            val data: ByteArray = msg.toArray()
            val outputStream = DataOutputStream(socket.getOutputStream())
            val size = data.size
            val arr: ByteArray = byteArrayOf(((size shr 8) and 0xFF).toByte(), (size and 0xFF).toByte())
            outputStream.write(arr)
            outputStream.write(data)
            outputStream.flush()

            val inStream = DataInputStream(socket.getInputStream())
            val readData = ByteArray(inStream.readUnsignedShort())
            inStream.read(readData)
            val time = (System.currentTimeMillis() - start).toInt()

            socket.close()
            socket = null
            if(!testResponse(DnsMessage(readData))) return null
            return time
        } catch (ex: Exception) {
            return null
        } finally {
            socket?.close()
        }
    }

    private fun createTestDnsPacket(): DnsMessage {
        val msg = DnsMessage.builder().setQrFlag(false)
            .addQuestion(Question(testDomains.random(), Record.TYPE.A, Record.CLASS.IN))
            .setId(Random.nextInt(1, 999999))
            .setRecursionDesired(true)
            .setAuthenticData(true)
            .setRecursionAvailable(true)
        return msg.build()
    }

    private fun testResponse(message:DnsMessage):Boolean {
        return message.answerSection.size > 0
    }

    private inner class PinnedDns(private val upstreamServers: List<UpstreamAddress>) : Dns {

        override fun lookup(hostname: String): MutableList<InetAddress> {
            val res = mutableListOf<InetAddress>()
            for (server in upstreamServers) {
                if (server.host.equals(hostname, true)) {
                    res.addAll(server.addressCreator.resolveOrGetResultOrNull(true) ?: emptyArray())
                    break
                }
            }
            if (res.isEmpty()) {
                res.addAll(Dns.SYSTEM.lookup(hostname))
            }
            if (res.isEmpty()) {
                throw UnknownHostException("Could not resolve $hostname")
            }
            return res
        }
    }
}