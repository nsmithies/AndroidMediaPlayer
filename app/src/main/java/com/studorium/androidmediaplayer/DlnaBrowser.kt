package com.studorium.androidmediaplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class DlnaMediaObject(
    val id: String,
    val parentId: String,
    val title: String,
    val isContainer: Boolean,

    // UPnP object type
    val objectClass: String,

    // Music metadata
    val artist: String,
    val album: String,
    val genre: String,
    val trackNumber: Int?,
    val date: String,
    val albumArtist: String = "",
    val discNumber: Int? = null,
    val year: String,

    // Artwork
    val albumArtUrl: String,

    // Audio resource
    val resourceUrl: String,
    val duration: String,
    val protocolInfo: String,
    val mimeType: String
) {

    val isAudioTrack: Boolean
        get() =
            !isContainer &&
                    (
                            objectClass.contains(
                                "audioItem",
                                ignoreCase = true
                            ) ||
                                    mimeType.startsWith(
                                        "audio/",
                                        ignoreCase = true
                                    )
                            )
}

data class DlnaBrowseResult(
    val items: List<DlnaMediaObject>,
    val numberReturned: Int,
    val totalMatches: Int
)

private data class DlnaBrowsePage(
    val items: List<DlnaMediaObject>,
    val numberReturned: Int,
    val totalMatches: Int
)

class DlnaBrowser {

    suspend fun browse(
        server: DlnaServer,
        objectId: String = "0"
    ): DlnaBrowseResult =
        withContext(
            Dispatchers.IO
        ) {

            browseAllPages(
                server = server,
                objectId = objectId
            )
        }

    private fun browseAllPages(
        server: DlnaServer,
        objectId: String
    ): DlnaBrowseResult {

        val visibleItems =
            mutableListOf<DlnaMediaObject>()

        var startingIndex =
            0

        var serverTotalMatches =
            0

        var firstRequest =
            true

        var pageCount =
            0

        val seenPageSignatures =
            mutableSetOf<String>()

        while (
            pageCount < MAX_PAGE_COUNT
        ) {

            val page =
                browsePage(
                    server = server,
                    objectId = objectId,
                    startingIndex = startingIndex,
                    requestedCount = PAGE_SIZE
                )

            pageCount++

            if (
                firstRequest
            ) {

                serverTotalMatches =
                    page.totalMatches

                firstRequest =
                    false

            } else if (
                page.totalMatches >
                serverTotalMatches
            ) {

                serverTotalMatches =
                    page.totalMatches
            }

            /*
             * Important:
             *
             * Pagination is based on what the SERVER
             * returned, not on how many items survive
             * our music-only filter.
             */
            if (
                page.numberReturned <= 0
            ) {

                break
            }

            /*
             * Detect broken DLNA servers that ignore
             * StartingIndex and repeatedly return the
             * same page.
             */
            val signature =
                page.items
                    .joinToString(
                        separator = "|"
                    ) { item ->

                        buildString {

                            append(
                                item.id
                            )

                            append(
                                ":"
                            )

                            append(
                                item.title
                            )

                            append(
                                ":"
                            )

                            append(
                                item.resourceUrl
                            )
                        }
                    }

            if (
                signature.isNotBlank() &&
                signature in seenPageSignatures
            ) {

                break
            }

            if (
                signature.isNotBlank()
            ) {

                seenPageSignatures.add(
                    signature
                )
            }

            /*
             * THIS is now the music-only filter used
             * by the visible DLNA browser.
             */
            val musicOnlyItems =
                page.items.filter {
                        item ->

                    shouldShowItem(
                        item
                    )
                }

            visibleItems.addAll(
                musicOnlyItems
            )

            /*
             * Advance according to the unfiltered
             * number returned by the DLNA server.
             */
            startingIndex +=
                page.numberReturned

            if (
                serverTotalMatches > 0 &&
                startingIndex >=
                serverTotalMatches
            ) {

                break
            }

            if (
                page.numberReturned <
                PAGE_SIZE
            ) {

                break
            }
        }

        /*
         * Remove accidental duplicates.
         */
        val uniqueItems =
            visibleItems.distinctBy {
                    item ->

                if (
                    item.resourceUrl
                        .isNotBlank()
                ) {

                    "resource:${item.resourceUrl}"

                } else {

                    "object:${item.id}:${item.parentId}"
                }
            }

        /*
         * totalMatches now describes the MUSIC items
         * visible to our app rather than Twonky's
         * complete photo/video/music count.
         */
        return DlnaBrowseResult(
            items =
                uniqueItems,

            numberReturned =
                uniqueItems.size,

            totalMatches =
                uniqueItems.size
        )
    }

    /*
     * Determines whether an object is allowed into
     * our application's visible library.
     */
    private fun shouldShowItem(
        item: DlnaMediaObject
    ): Boolean {

        /*
         * Regular media items:
         *
         * ONLY audio is allowed.
         *
         * Images and video can never appear as
         * playable library items.
         */
        if (
            !item.isContainer
        ) {

            return item.isAudioTrack
        }

        /*
         * Containers require a different rule because
         * a music container does not itself have an
         * audio MIME type.
         */

        if (
            isObviouslyNonMusicClass(
                item.objectClass
            )
        ) {

            return false
        }

        if (
            isObviouslyNonMusicFolder(
                item.title
            )
        ) {

            return false
        }

        return true
    }

    /*
     * Some DLNA servers expose meaningful UPnP
     * classes for their containers.
     */
    private fun isObviouslyNonMusicClass(
        objectClass: String
    ): Boolean {

        val value =
            objectClass
                .trim()
                .lowercase()

        if (
            value.isBlank()
        ) {

            return false
        }

        return (
                value.contains(
                    "video"
                ) ||
                        value.contains(
                            "image"
                        ) ||
                        value.contains(
                            "photo"
                        ) ||
                        value.contains(
                            "movie"
                        )
                )
    }

    /*
     * Twonky and similar servers often expose
     * virtual folders such as:
     *
     * @photos@
     * @videos@
     * Photos
     * Pictures
     * Movies
     *
     * Normalize punctuation first so @photos@
     * becomes "photos".
     */
    private fun isObviouslyNonMusicFolder(
        title: String
    ): Boolean {

        val normalized =
            title
                .trim()
                .lowercase()
                .replace(
                    Regex("[^a-z0-9]+"),
                    " "
                )
                .trim()

        if (
            normalized.isBlank()
        ) {

            return false
        }

        val words =
            normalized
                .split(
                    Regex("\\s+")
                )
                .filter {
                    it.isNotBlank()
                }
                .toSet()

        val blockedWords =
            setOf(
                "photo",
                "photos",
                "photograph",
                "photographs",
                "picture",
                "pictures",
                "image",
                "images",
                "video",
                "videos",
                "movie",
                "movies",
                "film",
                "films"
            )

        return words.any {
                word ->

            word in blockedWords
        }
    }

    private fun browsePage(
        server: DlnaServer,
        objectId: String,
        startingIndex: Int,
        requestedCount: Int
    ): DlnaBrowsePage {

        val soapBody =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope
                xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
                s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
                <s:Body>
                    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
                        <ObjectID>${escapeXml(objectId)}</ObjectID>
                        <BrowseFlag>BrowseDirectChildren</BrowseFlag>
                        <Filter>*</Filter>
                        <StartingIndex>$startingIndex</StartingIndex>
                        <RequestedCount>$requestedCount</RequestedCount>
                        <SortCriteria></SortCriteria>
                    </u:Browse>
                </s:Body>
            </s:Envelope>
            """.trimIndent()

        val connection =
            URL(
                server.controlUrl
            )
                .openConnection()
                    as HttpURLConnection

        connection.requestMethod =
            "POST"

        connection.connectTimeout =
            CONNECTION_TIMEOUT

        connection.readTimeout =
            READ_TIMEOUT

        connection.doOutput =
            true

        connection.setRequestProperty(
            "Content-Type",
            "text/xml; charset=\"utf-8\""
        )

        connection.setRequestProperty(
            "SOAPACTION",
            "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\""
        )

        connection.setRequestProperty(
            "Connection",
            "close"
        )

        val bodyBytes =
            soapBody.toByteArray(
                Charsets.UTF_8
            )

        connection.setFixedLengthStreamingMode(
            bodyBytes.size
        )

        try {

            connection
                .outputStream
                .use { output ->

                    output.write(
                        bodyBytes
                    )
                }

            val responseCode =
                connection.responseCode

            val responseText =
                if (
                    responseCode in
                    200..299
                ) {

                    connection
                        .inputStream
                        .bufferedReader()
                        .use {
                                reader ->

                            reader.readText()
                        }

                } else {

                    val errorText =
                        connection
                            .errorStream
                            ?.bufferedReader()
                            ?.use {
                                    reader ->

                                reader.readText()
                            }
                            ?: ""

                    throw Exception(
                        "DLNA server returned HTTP " +
                                "$responseCode\n$errorText"
                    )
                }

            return parseBrowsePage(
                soapXml =
                    responseText,

                server =
                    server
            )

        } finally {

            connection.disconnect()
        }
    }

    private fun parseBrowsePage(
        soapXml: String,
        server: DlnaServer
    ): DlnaBrowsePage {

        val soapDocument =
            parseXml(
                soapXml
            )

        val resultNodes =
            soapDocument
                .getElementsByTagNameNS(
                    "*",
                    "Result"
                )

        if (
            resultNodes.length == 0
        ) {

            throw Exception(
                "Browse response did not contain a Result element."
            )
        }

        val didlXml =
            resultNodes
                .item(
                    0
                )
                ?.textContent
                ?.trim()
                ?: ""

        val numberReturned =
            firstElementText(
                soapDocument,
                "NumberReturned"
            )
                .toIntOrNull()
                ?: 0

        val totalMatches =
            firstElementText(
                soapDocument,
                "TotalMatches"
            )
                .toIntOrNull()
                ?: 0

        if (
            didlXml.isBlank()
        ) {

            return DlnaBrowsePage(
                items =
                    emptyList(),

                numberReturned =
                    numberReturned,

                totalMatches =
                    totalMatches
            )
        }

        val didlDocument =
            parseXml(
                didlXml
            )

        val mediaObjects =
            mutableListOf<DlnaMediaObject>()

        /*
         * Preserve the ordering supplied by the
         * DLNA server.
         */
        val root =
            didlDocument.documentElement

        val children =
            root.childNodes

        for (
        index in
        0 until children.length
        ) {

            val node =
                children.item(
                    index
                )

            if (
                node.nodeType !=
                Node.ELEMENT_NODE
            ) {

                continue
            }

            val element =
                node as? Element
                    ?: continue

            val localName =
                element.localName
                    ?: element.nodeName
                        .substringAfter(
                            ":"
                        )

            when (
                localName.lowercase()
            ) {

                "container" -> {

                    mediaObjects.add(
                        parseMediaObject(
                            element =
                                element,

                            isContainer =
                                true,

                            server =
                                server
                        )
                    )
                }

                "item" -> {

                    mediaObjects.add(
                        parseMediaObject(
                            element =
                                element,

                            isContainer =
                                false,

                            server =
                                server
                        )
                    )
                }
            }
        }

        return DlnaBrowsePage(
            items =
                mediaObjects,

            numberReturned =
                numberReturned,

            totalMatches =
                totalMatches
        )
    }

    private fun parseMediaObject(
        element: Element,
        isContainer: Boolean,
        server: DlnaServer
    ): DlnaMediaObject {

        val id =
            element.getAttribute(
                "id"
            )

        val parentId =
            element.getAttribute(
                "parentID"
            )

        val title =
            childText(
                element,
                "title"
            ).ifBlank {
                "(Untitled)"
            }

        val objectClass =
            childText(
                element,
                "class"
            )

        val artist =
            firstNonBlank(
                childText(
                    element,
                    "artist"
                ),

                childText(
                    element,
                    "creator"
                )
            )

        val album =
            childText(
                element,
                "album"
            )

        val genre =
            childText(
                element,
                "genre"
            )

        val trackNumber =
            firstNonBlank(
                childText(
                    element,
                    "originalTrackNumber"
                ),

                childText(
                    element,
                    "trackNumber"
                )
            )
                .toIntOrNull()

        val albumArtist = firstNonBlank(childText(element, "albumArtist"), childText(element, "albumartist"))

        val discNumber = firstNonBlank(
            childText(element, "originalDiscNumber"),
            childText(element, "discNumber"),
            childText(element, "disc")
        ).toIntOrNull()

        val date =
            childText(
                element,
                "date"
            )

        val year =
            extractYear(
                date
            )

        val rawAlbumArtUrl =
            firstNonBlank(
                childText(
                    element,
                    "albumArtURI"
                ),

                childText(
                    element,
                    "icon"
                )
            )

        val albumArtUrl =
            resolveUrl(
                baseUrl =
                    server.location,

                value =
                    rawAlbumArtUrl
            )

        val resource =
            findBestResource(
                element
            )

        val rawResourceUrl =
            resource
                ?.textContent
                ?.trim()
                ?: ""

        val resourceUrl =
            resolveUrl(
                baseUrl =
                    server.location,

                value =
                    rawResourceUrl
            )

        val duration =
            resource
                ?.getAttribute(
                    "duration"
                )
                ?.trim()
                ?: ""

        val protocolInfo =
            resource
                ?.getAttribute(
                    "protocolInfo"
                )
                ?.trim()
                ?: ""

        val mimeType =
            extractMimeType(
                protocolInfo
            )

        return DlnaMediaObject(
            id =
                id,

            parentId =
                parentId,

            title =
                title,

            isContainer =
                isContainer,

            objectClass =
                objectClass,

            artist =
                artist,

            album =
                album,

            genre =
                genre,

            trackNumber =
                trackNumber,

            date =
                date,

            albumArtist = albumArtist,
            discNumber = discNumber,

            year =
                year,

            albumArtUrl =
                albumArtUrl,

            resourceUrl =
                resourceUrl,

            duration =
                duration,

            protocolInfo =
                protocolInfo,

            mimeType =
                mimeType
        )
    }

    private fun findBestResource(
        element: Element
    ): Element? {

        val resources =
            element
                .getElementsByTagNameNS(
                    "*",
                    "res"
                )

        if (
            resources.length == 0
        ) {

            return null
        }

        var firstResource:
                Element? = null

        for (
        index in
        0 until resources.length
        ) {

            val resource =
                resources
                    .item(
                        index
                    ) as? Element
                    ?: continue

            if (
                firstResource == null
            ) {

                firstResource =
                    resource
            }

            val protocolInfo =
                resource
                    .getAttribute(
                        "protocolInfo"
                    )
                    .trim()

            val mimeType =
                extractMimeType(
                    protocolInfo
                )

            if (
                mimeType.startsWith(
                    "audio/",
                    ignoreCase =
                        true
                )
            ) {

                return resource
            }
        }

        return firstResource
    }

    private fun extractMimeType(
        protocolInfo: String
    ): String {

        if (
            protocolInfo.isBlank()
        ) {

            return ""
        }

        val parts =
            protocolInfo.split(
                ":",
                limit =
                    4
            )

        if (
            parts.size < 3
        ) {

            return ""
        }

        return parts[2]
            .trim()
    }

    private fun resolveUrl(
        baseUrl: String,
        value: String
    ): String {

        if (
            value.isBlank()
        ) {

            return ""
        }

        return try {

            URL(
                URL(
                    baseUrl
                ),
                value
            ).toString()

        } catch (
            _: Exception
        ) {

            value
        }
    }

    private fun extractYear(
        date: String
    ): String {

        if (
            date.length >= 4
        ) {

            val possibleYear =
                date.substring(
                    0,
                    4
                )

            if (
                possibleYear.all {
                        character ->

                    character.isDigit()
                }
            ) {

                return possibleYear
            }
        }

        return ""
    }

    private fun parseXml(
        xml: String
    ) =
        DocumentBuilderFactory
            .newInstance()
            .apply {

                isNamespaceAware =
                    true
            }
            .newDocumentBuilder()
            .parse(
                InputSource(
                    StringReader(
                        xml
                    )
                )
            )

    private fun firstElementText(
        document:
        org.w3c.dom.Document,
        name: String
    ): String {

        val nodes =
            document
                .getElementsByTagNameNS(
                    "*",
                    name
                )

        if (
            nodes.length == 0
        ) {

            return ""
        }

        return nodes
            .item(
                0
            )
            ?.textContent
            ?.trim()
            ?: ""
    }

    private fun childText(
        parent: Element,
        name: String
    ): String {

        val nodes =
            parent
                .getElementsByTagNameNS(
                    "*",
                    name
                )

        if (
            nodes.length == 0
        ) {

            return ""
        }

        return nodes
            .item(
                0
            )
            ?.textContent
            ?.trim()
            ?: ""
    }

    private fun firstNonBlank(
        vararg values: String
    ): String {

        for (
        value in
        values
        ) {

            if (
                value.isNotBlank()
            ) {

                return value
            }
        }

        return ""
    }

    private fun escapeXml(
        value: String
    ): String {

        return value
            .replace(
                "&",
                "&amp;"
            )
            .replace(
                "<",
                "&lt;"
            )
            .replace(
                ">",
                "&gt;"
            )
            .replace(
                "\"",
                "&quot;"
            )
            .replace(
                "'",
                "&apos;"
            )
    }

    companion object {

        private const val PAGE_SIZE =
            100

        private const val MAX_PAGE_COUNT =
            10_000

        private const val CONNECTION_TIMEOUT =
            5_000

        private const val READ_TIMEOUT =
            10_000
    }
}