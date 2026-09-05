package me.ash.reader.llm.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CitationReturnDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longAnswer_returnsToCitedParagraph_andRepeatsAfterScrollingAway() {
        val placement = LlmCitationReturnPlacement()
        lateinit var listState: LazyListState
        var request by mutableStateOf(0)
        var completed by mutableStateOf(0)
        val markdown = (1..45).joinToString("\n\n") { index ->
            if (index == 32) "Target evidence from article B [2]."
            else "Paragraph $index. Background context for the comparison across three articles."
        }
        compose.setContent {
            MaterialTheme {
                listState = rememberLazyListState()
                Box(Modifier.width(384.dp).height(420.dp)) {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        modifier = Modifier.onGloballyPositioned {
                            placement.viewportBounds = Rect(it.positionInWindow(), it.size.toSize())
                        },
                    ) {
                        item(key = "user") { Text("Compare A, B and C.") }
                        item(key = "answer") {
                            LlmRichMarkdown(
                                markdown = markdown,
                                validCitationIndices = setOf(2),
                                citationReturnDisplayOrder = 2,
                                citationReturnBlockIndex = 31,
                                citationReturnHighlighted = completed > 0,
                                onCitationParagraphPositioned = { placement.paragraphBounds = it },
                            )
                        }
                        item(key = "end") { Text("End of conversation") }
                    }
                }
                LaunchedEffect(request) {
                    if (request > 0) {
                        if (request > 1) listState.scrollToItem(0)
                        listState.scrollToCitationParagraph(1, "answer", placement)
                        completed = request
                    }
                }
            }
        }
        repeat(2) { attempt ->
            compose.runOnIdle { request = attempt + 1 }
            compose.waitUntil(10_000) { completed == attempt + 1 }
            compose.runOnIdle {
                val paragraph = requireNotNull(placement.paragraphBounds)
                val viewport = requireNotNull(placement.viewportBounds)
                assertTrue("Citation paragraph must be visible: $paragraph inside $viewport",
                    paragraph.top >= viewport.top && paragraph.bottom <= viewport.bottom)
                assertTrue("Citation paragraph must be centered, not the answer's beginning",
                    abs(paragraph.center.y - viewport.center.y) < 4f)
            }
        }
    }
}
