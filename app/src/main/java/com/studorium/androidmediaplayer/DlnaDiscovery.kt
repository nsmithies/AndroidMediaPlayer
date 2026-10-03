package com.studorium.androidmediaplayer

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class DlnaServer(
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val location: String,
    val controlUrl: String
)

data class DlnaDiagnostic(
    val location: String,
    val result: String
)

data class DlnaDiscoveryResult(
    val servers: List<DlnaServer>,
    val ssdpLocationCount: Int,
    val diagnostics: List<DlnaDiagnostic>
)

class DlnaDiscovery(
    private val context: Context
) {

    suspend fun discover(): DlnaDiscoveryResult =
        withContext(Dispatchers.IO) {

            val locations =
                discoverLocations()

            val servers =
                mutableListOf<DlnaServer>()

            val diagnostics =
                mutableListOf<DlnaDiagnostic>()

            for (location in locations) {

                try {

                    val server =
                        readMediaServer(location)

                    if (server != null) {

                        servers.add(server)

                        diagnostics.add(
                            DlnaDiagnostic(
                                location = location,
                                result =
                                    "ACCEPTED: ${server.friendlyName}"
                            )
                        )

                    } else {

                        diagnostics.add(
                            DlnaDiagnostic(
                                location = location,
                                result =
                                    "Rejected: no ContentDirectory found"
                            )
                        )
                    }

                } catch (e: Exception) {

                    diagnostics.add(
                        DlnaDiagnostic(
                            location = location,
                            result =
                                "ERROR: ${e.javaClass.simpleName}: " +
                                        (e.message ?: "Unknown error")
                        )
                    )
                }
            }

            DlnaDiscoveryResult(
                servers =
                    servers
                        .distinctBy {
                            it.controlUrl
                        }
                        .sortedBy {
                            it.friendlyName.lowercase()
                        },

                ssdpLocationCount =
                    locations.size,

                diagnostics =
                    diagnostics
            )
        }

    private fun discoverLocations(): Set<String> {

        val locations =
            mutableSetOf<String>()

        val wifiManager =
            context.applicationContext
                .getSystemService(
                    Context.WIFI_SERVICE
                ) as WifiManager

        val multicastLock =
            wifiManager.createMulticastLock(
                "AndroidMediaPlayer-DLNA"
            )

        multicastLock.setReferenceCounted(false)
        multicastLock.acquire()

        var socket: MulticastSocket? = null

        try {

            socket =
                MulticastSocket()

            socket.soTimeout = 1000

            val multicastAddress =
                InetAddress.getByName(
                    "239.255.255.250"
                )

            val request =
                buildString {

                    append(
                        "M-SEARCH * HTTP/1.1\r\n"
                    )

                    append(
                        "HOST: 239.255.255.250:1900\r\n"
                    )

                    append(
                        "MAN: \"ssdp:discover\"\r\n"
                    )

                    append(
                        "MX: 2\r\n"
                    )

                    append(
                        "ST: ssdp:all\r\n"
                    )

                    append("\r\n")
                }

            val bytes =
                request.toByteArray(
                    Charsets.UTF_8
                )

            val packet =
                DatagramPacket(
                    bytes,
                    bytes.size,
                    multicastAddress,
                    1900
                )

            socket.send(packet)

            Thread.sleep(250)

            socket.send(packet)

            val endTime =
                System.currentTimeMillis() +
                        6000

            while (
                System.currentTimeMillis() <
                endTime
            ) {

                try {

                    val buffer =
                        ByteArray(16384)

                    val responsePacket =
                        DatagramPacket(
                            buffer,
                            buffer.size
                        )

                    socket.receive(
                        responsePacket
                    )

                    val response =
                        String(
                            responsePacket.data,
                            0,
                            responsePacket.length,
                            Charsets.UTF_8
                        )

                    val headers =
                        parseHeaders(
                            response
                        )

                    val location =
                        headers["location"]

                    if (
                        !location.isNullOrBlank()
                    ) {

                        locations.add(
                            location
                        )
                    }

                } catch (
                    _: java.net.SocketTimeoutException
                ) {
                    // Continue until discovery window expires.
                }
            }

        } finally {

            socket?.close()

            if (
                multicastLock.isHeld
            ) {
                multicastLock.release()
            }
        }

        return locations
    }

    private fun readMediaServer(
        location: String
    ): DlnaServer? {

        val descriptionUrl =
            URL(location)

        val connection =
            descriptionUrl
                .openConnection()
                    as HttpURLConnection

        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = "GET"

        connection.setRequestProperty(
            "Accept",
            "text/xml, application/xml, */*"
        )

        connection.setRequestProperty(
            "Connection",
            "close"
        )

        try {

            val responseCode =
                connection.responseCode

            if (
                responseCode !in 200..299
            ) {

                throw Exception(
                    "HTTP $responseCode"
                )
            }

            connection.inputStream.use { input ->

                val factory =
                    DocumentBuilderFactory
                        .newInstance()

                factory.isNamespaceAware =
                    true

                val document =
                    factory
                        .newDocumentBuilder()
                        .parse(input)

                document
                    .documentElement
                    .normalize()

                val deviceNodes =
                    document
                        .getElementsByTagNameNS(
                            "*",
                            "device"
                        )

                for (
                deviceIndex in
                0 until deviceNodes.length
                ) {

                    val device =
                        deviceNodes
                            .item(deviceIndex)
                                as? Element
                            ?: continue

                    val friendlyName =
                        childText(
                            device,
                            "friendlyName"
                        ).ifBlank {
                            "DLNA Media Server"
                        }

                    val manufacturer =
                        childText(
                            device,
                            "manufacturer"
                        )

                    val modelName =
                        childText(
                            device,
                            "modelName"
                        )

                    val serviceNodes =
                        device
                            .getElementsByTagNameNS(
                                "*",
                                "service"
                            )

                    for (
                    serviceIndex in
                    0 until serviceNodes.length
                    ) {

                        val service =
                            serviceNodes
                                .item(serviceIndex)
                                    as? Element
                                ?: continue

                        val serviceType =
                            childText(
                                service,
                                "serviceType"
                            )

                        if (
                            serviceType.contains(
                                "ContentDirectory",
                                ignoreCase = true
                            )
                        ) {

                            val rawControlUrl =
                                childText(
                                    service,
                                    "controlURL"
                                )

                            if (
                                rawControlUrl.isBlank()
                            ) {
                                continue
                            }

                            val controlUrl =
                                URL(
                                    descriptionUrl,
                                    rawControlUrl
                                ).toString()

                            return DlnaServer(
                                friendlyName =
                                    friendlyName,
                                manufacturer =
                                    manufacturer,
                                modelName =
                                    modelName,
                                location =
                                    location,
                                controlUrl =
                                    controlUrl
                            )
                        }
                    }
                }
            }

        } finally {

            connection.disconnect()
        }

        return null
    }

    private fun childText(
        parent: Element,
        tagName: String
    ): String {

        val nodes =
            parent.getElementsByTagNameNS(
                "*",
                tagName
            )

        if (
            nodes.length == 0
        ) {
            return ""
        }

        return nodes
            .item(0)
            ?.textContent
            ?.trim()
            ?: ""
    }

    private fun parseHeaders(
        response: String
    ): Map<String, String> {

        val headers =
            mutableMapOf<String, String>()

        response
            .lines()
            .drop(1)
            .forEach { line ->

                val separator =
                    line.indexOf(':')

                if (
                    separator > 0
                ) {

                    val name =
                        line
                            .substring(
                                0,
                                separator
                            )
                            .trim()
                            .lowercase()

                    val value =
                        line
                            .substring(
                                separator + 1
                            )
                            .trim()

                    headers[name] =
                        value
                }
            }

        return headers
    }
}