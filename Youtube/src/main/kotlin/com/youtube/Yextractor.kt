package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        private val ytVideos = ConcurrentHashMap<String, List<ExtractorLink>>()
        private val ytVideosSubtitles = ConcurrentHashMap<String, List<SubtitlesStream>>()

        private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val manifestMap = ConcurrentHashMap<String, String>()
    }

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/watch?v=$id"
    }

    data class StreamInfo(
        val url: String,
        val mimeType: String,
        val height: Int,
        val label: String,
        val initRange: String?,
        val indexRange: String?
    )

    data class AudioInfo(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val initRange: String?,
        val indexRange: String?,
        val language: String
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = try { 
            cleanedUrl.substringAfter("v=").substringBefore("&") 
        } catch (e: Exception) { 
            cleanedUrl 
        }

        // Return cached results if available
        if (ytVideos.containsKey(videoId)) {
            ytVideos[videoId]?.forEach { callback(it) }
            ytVideosSubtitles[videoId]?.mapNotNull { ss ->
                try {
                    val lang = ss.locale?.language ?: return@mapNotNull null
                    val content = ss.content ?: ss.getUrl() ?: return@mapNotNull null
                    newSubtitleFile(lang, content)
                } catch (e: Exception) { null }
            }?.forEach { subtitleCallback(it) }
            return
        }

        try {
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            s.fetchPage()

            val durationSeconds = if (s.length > 0) s.length else 3600L
            val builtLinks = mutableListOf<ExtractorLink>()
            val seenUrls = mutableSetOf<String>()

            val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                try {
                    val streamUrl = vs.content ?: return@mapNotNull null
                    if (!seenUrls.add(streamUrl)) return@mapNotNull null

                    val label = buildVideoLabelNumber(vs)
                    val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    var mime = vs.format?.mimeType
                    if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(streamUrl, false)

                    val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                    val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null

                    StreamInfo(streamUrl, mime, height, label, initR, indexR)
                } catch (e: Exception) { null }
            }.distinctBy { it.height }

            val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                try {
                    val aUrl = asr.content ?: return@mapNotNull null
                    val bitrate = asr.bitrate ?: 128000
                    var mime = asr.format?.mimeType
                    if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(aUrl, true)

                    val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                    val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null

                    var rawLang = asr.audioTrackId ?: "Default"
                    if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")

                    AudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase())
                } catch (e: Exception) { null }
            }.distinctBy { it.url }

            val audiosByLanguage = audioInfoList.groupBy { it.language }

            try {
                val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                if (subs.isNotEmpty()) {
                    ytVideosSubtitles[videoId] = subs
                }
            } catch (e: Exception) { }

            startServerIfNeeded()

            // 1. Recreate high-quality options via Local DASH
            for (video in videoOnlyList) {
                if (audiosByLanguage.isNotEmpty()) {
                    for ((lang, audios) in audiosByLanguage) {
                        val bestAudioForLang = if (video.mimeType.contains("webm")) {
                            audios.sortedWith(compareByDescending<AudioInfo> { it.mimeType.contains("webm") }.thenByDescending { it.bitrate }).firstOrNull()
                        } else {
                            audios.sortedWith(compareByDescending<AudioInfo> { it.mimeType.contains("mp4") }.thenByDescending { it.bitrate }).firstOrNull()
                        }

                        if (bestAudioForLang != null) {
                            val singleAudioList = listOf(bestAudioForLang)
                            val dashXml = buildDashManifestXml(video, singleAudioList, durationSeconds)
                            val localLink = registerManifestAndGetUrl(dashXml)

                            if (localLink != null) {
                                val finalName = "${video.label}p ($lang)"
                                builtLinks.add(
                                    newExtractorLink(
                                        this.name,
                                        finalName,
                                        localLink,
                                        type = ExtractorLinkType.DASH
                                    ) {
                                        this.referer = mainUrl
                                        this.quality = video.height
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 2. Fallback to Legacy/Muxed Stream Qualities
            val muxedList = (s.videoStreams ?: emptyList()).mapNotNull { vs ->
                try {
                    val mUrl = vs.content ?: return@mapNotNull null
                    if (!seenUrls.add(mUrl)) return@mapNotNull null
                    val label = buildVideoLabelNumber(vs)
                    val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                    Triple(mUrl, label, height)
                } catch (e: Exception) { null }
            }
            
            for ((mUrl, mLabel, mHeight) in muxedList) {
                builtLinks.add(
                    newExtractorLink(this.name, "${mLabel}p (Legacy)", mUrl, type = INFER_TYPE) {
                        this.referer = mainUrl
                        this.quality = mHeight
                    }
                )
            }

            if (builtLinks.isNotEmpty()) {
                ytVideos[videoId] = builtLinks.toList()
            }

        } catch (e: Exception) { 
            logError(e) 
        }

        ytVideos[videoId]?.forEach { callback(it) }

        ytVideosSubtitles[videoId]?.mapNotNull { ss ->
            try {
                val lang = ss.locale?.language ?: return@mapNotNull null
                val content = ss.content ?: ss.getUrl() ?: return@mapNotNull null
                newSubtitleFile(lang, content)
            } catch (e: Exception) { null }
        }?.forEach { subtitleCallback(it) }
    }

    @Synchronized
    private fun startServerIfNeeded() {
        if (activeServer != null && !activeServer!!.isClosed) return
        try {
            activeServer = ServerSocket(0)
            serverPort = activeServer!!.localPort
            thread {
                try {
                    while (activeServer != null && !activeServer!!.isClosed) {
                        val client = activeServer!!.accept()
                        thread { handleClient(client) }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) { logError(e) }
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = PrintWriter(socket.getOutputStream(), true)
                val line = reader.readLine()
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        var path = parts[1].substring(1)
                        if (path.endsWith(".mpd")) path = path.replace(".mpd", "")
                        val content = manifestMap[path]
                        if (content != null) {
                            output.println("HTTP/1.1 200 OK")
                            output.println("Content-Type: application/dash+xml")
                            output.println("Connection: close")
                            output.println("Access-Control-Allow-Origin: *")
                            output.println("")
                            output.println(content)
                        } else {
                            output.println("HTTP/1.1 404 Not Found")
                            output.println("")
                        }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val cleanVideoUrl = escapeXml(video.url)
        val durationString = "PT${durationSec}S"

        val sb = StringBuilder()
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationString">""")
        sb.append("<Period>")

        val vMime = video.mimeType
        val vCodecs = if (vMime.contains("webm")) "vp9" else "avc1.4d401f"

        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""

        sb.append("""
            <AdaptationSet mimeType="$vMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="4000000" width="0" height="${video.height}" codecs="$vCodecs">
                <BaseURL>$cleanVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        audioList.forEachIndexed { index, audio ->
            val cleanAudioUrl = escapeXml(audio.url)
            val audioId = "audio_$index"
            val aMime = audio.mimeType
            val aCodecs = if (aMime.contains("webm")) "opus" else "mp4a.40.2"

            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="$audioId" bandwidth="${if (audio.bitrate > 0) audio.bitrate else 128000}" codecs="$aCodecs">
                    <BaseURL>$cleanAudioUrl</BaseURL>
                    $aSegmentBase
                  </Representation>
                </AdaptationSet>
            """.trimIndent())
        }

        sb.append("</Period>")
        sb.append("</MPD>")
        return sb.toString()
    }

    private fun escapeXml(url: String): String {
        return url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun getMimeTypeFromUrl(url: String, isAudio: Boolean): String {
        return try {
            val decoded = URLDecoder.decode(url, "UTF-8")
            if (decoded.contains("video/webm") || decoded.contains("audio/webm")) {
                if (isAudio) "audio/webm" else "video/webm"
            } else {
                if (isAudio) "audio/mp4" else "video/mp4"
            }
        } catch (e: Exception) { 
            if (isAudio) "audio/mp4" else "video/mp4" 
        }
    }

    private fun buildVideoLabelNumber(vs: org.schabi.newpipe.extractor.stream.VideoStream): String {
        val height = runCatching { vs.height }.getOrNull() ?: 0
        if (height > 0) return height.toString()
        return "video"
    }
}
