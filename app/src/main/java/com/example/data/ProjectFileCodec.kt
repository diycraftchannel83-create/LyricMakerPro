package com.winsmediatama.lyricmakerpro.data

import androidx.compose.ui.text.style.TextAlign
import com.winsmediatama.lyricmakerpro.model.VideoProject
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Portable JSON project file codec. TextAlign is adapted explicitly because it is a
 * Compose value type rather than a regular Kotlin enum.
 */
object ProjectFileCodec {
    private val adapter: JsonAdapter<VideoProject> by lazy {
        val textAlignAdapter = object : JsonAdapter<TextAlign>() {
            override fun fromJson(reader: JsonReader): TextAlign {
                return when (reader.nextString().lowercase()) {
                    "left" -> TextAlign.Left
                    "right" -> TextAlign.Right
                    "start" -> TextAlign.Start
                    "end" -> TextAlign.End
                    "justify" -> TextAlign.Justify
                    else -> TextAlign.Center
                }
            }

            override fun toJson(writer: JsonWriter, value: TextAlign?) {
                val encoded = when (value) {
                    TextAlign.Left -> "left"
                    TextAlign.Right -> "right"
                    TextAlign.Start -> "start"
                    TextAlign.End -> "end"
                    TextAlign.Justify -> "justify"
                    else -> "center"
                }
                writer.value(encoded)
            }
        }

        Moshi.Builder()
            .add(TextAlign::class.java, textAlignAdapter)
            .add(KotlinJsonAdapterFactory())
            .build()
            .adapter(VideoProject::class.java)
    }

    fun encode(project: VideoProject): String = adapter.toJson(project)

    fun decode(json: String): VideoProject =
        requireNotNull(adapter.fromJson(json)) { "Project file is empty or invalid." }
}
