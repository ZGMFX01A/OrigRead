/*
 * Feeder: Android RSS reader app
 * https://gitlab.com/spacecowboy/Feeder
 *
 * Copyright (C) 2022  Jonas Kalderstam
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package me.ash.reader.ui.component.reader

import android.content.Context
import android.util.Log
import androidx.compose.foundation.lazy.LazyListScope
import me.ash.reader.R
import org.jsoup.nodes.Element

@Suppress("FunctionName")
fun LazyListScope.Reader(
    context: Context,
    subheadUpperCase: Boolean = false,
    link: String,
    content: String,
    parsedBody: Element? = null,
    onImageClick: ((imgUrl: String, altText: String) -> Unit)? = null,
    onLinkClick: (String) -> Unit,
    anchorMapBuilder: NativeReaderAnchorMap.Builder? = null,
    nativeReaderAnchorState: NativeReaderAnchorState? = null,
    anchorHighlight: NativeReaderAnchorHighlight? = null,
    markerSnapshot: ReaderEvidenceMarkerSnapshot? = null,
    markerArticleId: String? = null,
) {
    if (parsedBody == null) {
        content.byteInputStream().use { inputStream ->
            htmlFormattedText(
                inputStream = inputStream,
                subheadUpperCase = subheadUpperCase,
                baseUrl = link,
                onImageClick = onImageClick,
                imagePlaceholder = R.drawable.origread_icon,
                onLinkClick = onLinkClick,
                anchorMapBuilder = anchorMapBuilder,
                nativeReaderAnchorState = nativeReaderAnchorState,
                anchorHighlight = anchorHighlight,
                markerSnapshot = markerSnapshot,
                markerArticleId = markerArticleId,
            )
        }
    } else {
        htmlFormattedText(
            body = parsedBody,
            subheadUpperCase = subheadUpperCase,
            baseUrl = link,
            onImageClick = onImageClick,
            imagePlaceholder = R.drawable.origread_icon,
            onLinkClick = onLinkClick,
            anchorMapBuilder = anchorMapBuilder,
            nativeReaderAnchorState = nativeReaderAnchorState,
            anchorHighlight = anchorHighlight,
            markerSnapshot = markerSnapshot,
            markerArticleId = markerArticleId,
        )
    }
}
