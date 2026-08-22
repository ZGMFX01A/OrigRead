package me.ash.reader.infrastructure.android

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Html
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.ash.reader.infrastructure.di.ApplicationScope
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext
    private val context: Context,
    @ApplicationScope
    private val coroutineScope: CoroutineScope
) {


    private val _stateFlow = MutableStateFlow<State>(State.Idle)
    val stateFlow = _stateFlow.asStateFlow()

    var state
        get() = stateFlow.value
        private set(value) {
            _stateFlow.value = value
        }

    private val tts: TextToSpeech = initTts()
    private val requestLock = Any()
    private var readJob: Job? = null
    private var requestId = 0L
    private var readingSegments: List<String> = emptyList()
    private var nextSegmentIndex = 0

    private fun initTts(): TextToSpeech {
        return TextToSpeech(context, TextToSpeech.OnInitListener {
            when (it) {
                TextToSpeech.SUCCESS -> {}
                else -> {
                    state = State.Error
                    Timber.e("TextToSpeech initialization failed $it")
                }
            }
        })
    }

    sealed interface State {
        object Idle : State
        object Preparing : State
        class Reading(val current: Int, val total: Int) : State {
            val progress: Float
                get() = current.toFloat() / total
        }

        object Error : State
    }


    fun readHtml(htmlContent: String) {
        synchronized(requestLock) {
            stopLocked()
            val currentRequestId = ++requestId
            readJob = coroutineScope.launch {
                val plainText =
                    Html.fromHtml(htmlContent, Html.FROM_HTML_MODE_LEGACY).toString()
                ensureActive()
                startReading(plainText, currentRequestId)
            }
        }
    }

    private fun startReading(text: String, readingRequestId: Long) {
        val detectedLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.detectLocaleFromText(text.take(text.lastIndex.coerceAtMost(500)))
                .firstOrNull()?.locale
        } else {
            null
        }
        val maxSpeechInputLength = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1)
        val textSegments = text
            .split("\n")
            .filterNot { it.isBlank() }
            .flatMap { it.chunked(maxSpeechInputLength) }
        val total = textSegments.size

        synchronized(requestLock) {
            if (readingRequestId != requestId || state != State.Idle) return
            state = State.Preparing
            tts.language = detectedLocale
            state = State.Reading(0, total)
            readingSegments = textSegments
            nextSegmentIndex = 0

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    synchronized(requestLock) {
                        val index = parseUtteranceIndex(utteranceId, readingRequestId)
                            ?: return@synchronized
                        state = State.Reading(index + 1, total)
                    }
                }

                override fun onDone(utteranceId: String?) {
                    synchronized(requestLock) {
                        val index = parseUtteranceIndex(utteranceId, readingRequestId)
                            ?: return@synchronized
                        if (index >= total - 1) {
                            readingSegments = emptyList()
                            state = State.Idle
                        } else {
                            speakNextSegmentLocked(readingRequestId)
                        }
                    }
                }

                override fun onError(utteranceId: String?) {
                    synchronized(requestLock) {
                        if (parseUtteranceIndex(utteranceId, readingRequestId) != null) {
                            readingSegments = emptyList()
                            state = State.Error
                        }
                    }
                }
            })
            if (textSegments.isEmpty()) {
                readingSegments = emptyList()
                state = State.Idle
            } else {
                speakNextSegmentLocked(readingRequestId)
            }
        }
    }

    private fun parseUtteranceIndex(utteranceId: String?, expectedRequestId: Long): Int? {
        val parts = utteranceId?.split(':', limit = 2) ?: return null
        if (parts.firstOrNull()?.toLongOrNull() != expectedRequestId) return null
        return parts.getOrNull(1)?.toIntOrNull()
    }

    private fun speakNextSegmentLocked(readingRequestId: Long) {
        if (readingRequestId != requestId) return
        val segment = readingSegments.getOrNull(nextSegmentIndex) ?: return
        val segmentIndex = nextSegmentIndex++
        val result = tts.speak(
            segment,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "$readingRequestId:$segmentIndex",
        )
        if (result == TextToSpeech.ERROR) {
            readingSegments = emptyList()
            state = State.Error
        }
    }

    fun stop() {
        synchronized(requestLock) {
            stopLocked()
        }
    }

    private fun stopLocked() {
        requestId++
        readJob?.cancel()
        readJob = null
        readingSegments = emptyList()
        nextSegmentIndex = 0
        tts.stop()
        state = State.Idle
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun Context.detectLocaleFromText(
    text: CharSequence,
    minConfidence: Float = 80.0f,
): Sequence<LocaleWithConfidence> {
    val textClassificationManager =
        getSystemService<TextClassificationManager>() ?: return emptySequence()
    val textClassifier = textClassificationManager.textClassifier

    val textRequest = TextLanguage.Request.Builder(text).build()
    val detectedLanguage = textClassifier.detectLanguage(textRequest)

    return sequence {
        for (i in 0 until detectedLanguage.localeHypothesisCount) {
            val localeDetected = detectedLanguage.getLocale(i)
            val confidence = detectedLanguage.getConfidenceScore(localeDetected) * 100.0f
            if (confidence >= minConfidence) {
                yield(
                    LocaleWithConfidence(
                        locale = localeDetected.toLocale(),
                        confidence = confidence,
                    ),
                )
            }
        }
    }
}

data class LocaleWithConfidence(
    val locale: Locale,
    val confidence: Float,
)
