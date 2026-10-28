package com.echomute

import java.io.InputStream
import java.net.URI
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class FeedEpisode(val id: String, val title: String, val audioUrl: String, val chaptersUrl: String? = null)
data class Feed(val title: String, val episodes: List<FeedEpisode>)

fun httpUrl(value: String): Boolean = try {
    val uri = URI(value)
    (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrEmpty() && uri.userInfo == null
} catch (_: Exception) { false }

fun parseFeed(input: InputStream): Feed {
    val parser = SAXParserFactory.newInstance().newSAXParser().xmlReader
    parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
    parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    var feedTitle = ""; var item = false; var episodeTitle = ""; var guid = ""; var audio = ""; var chapters: String? = null
    val episodes = mutableListOf<FeedEpisode>()
    val text = StringBuilder()
    val handler = object : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource("".byteInputStream())
        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            text.setLength(0)
            if (qName.equals("item", true)) { item = true; episodeTitle = ""; guid = ""; audio = ""; chapters = null }
            if (item && qName.equals("enclosure", true)) {
                val url = attributes.getValue("url").orEmpty().trim()
                val type = attributes.getValue("type").orEmpty()
                if (httpUrl(url) && (type.startsWith("audio/") || type.isBlank())) audio = url
            }
            if (item && qName.equals("podcast:chapters", true)) {
                val url = attributes.getValue("url").orEmpty().trim()
                if (httpUrl(url)) chapters = url
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            when (qName.lowercase()) {
                "title" -> if (item) episodeTitle = value else if (feedTitle.isEmpty()) feedTitle = value
                "guid" -> if (item) guid = value
                "item" -> { if (audio.isNotEmpty()) episodes.add(FeedEpisode(guid.ifEmpty { audio }, episodeTitle.ifEmpty { "Untitled episode" }, audio, chapters)); item = false }
            }
            text.setLength(0)
        }
    }
    parser.contentHandler = handler; parser.entityResolver = handler
    parser.parse(InputSource(input))
    require(feedTitle.isNotBlank()) { "Feed has no title" }
    return Feed(feedTitle, episodes)
}
