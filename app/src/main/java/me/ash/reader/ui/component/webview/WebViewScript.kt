package me.ash.reader.ui.component.webview

object WebViewScript {

    fun get(boldCharacters: Boolean) = """
const BR_WORD_STEM_PERCENTAGE = 0.7;
const MAX_FIXATION_PARTS = 4;
const FIXATION_LOWER_BOUND = 0
function highlightText(sentenceText) {
    const matches = [...sentenceText.matchAll(/\p{L}+/gu)];
    if (matches.length === 0) return null;
    const span = document.createElement('br-span');
    let offset = 0;
    matches.forEach(function(match) {
        span.appendChild(document.createTextNode(sentenceText.slice(offset, match.index)));
        const word = match[0];
        const stem = word.length > 3 ? Math.round(word.length * BR_WORD_STEM_PERCENTAGE) : word.length;
        const bold = document.createElement('br-bold');
        makeFixations(bold, word.slice(0, stem));
        span.appendChild(bold);
        if (stem < word.length) {
            const edge = document.createElement('br-edge');
            makeFixations(edge, word.slice(stem));
            span.appendChild(edge);
        }
        offset = match.index + word.length;
    });
    span.appendChild(document.createTextNode(sentenceText.slice(offset)));
    return span;
}

function makeFixations(parent, textContent) {
    const parts = Math.min(MAX_FIXATION_PARTS, textContent.length);
    if (parts === 0) return;
    const width = Math.ceil(textContent.length / parts);
    for (let index = 0; index < parts; index++) {
        const fixation = document.createElement('br-fixation');
        fixation.setAttribute('fixation-strength', String(index + 1));
        fixation.appendChild(document.createTextNode(textContent.slice(index * width, (index + 1) * width)));
        parent.appendChild(fixation);
    }
}

const IGNORE_NODE_TAGS = ['STYLE', 'SCRIPT', 'BR-SPAN', 'BR-FIXATION', 'BR-BOLD', 'BR-EDGE', 'SVG', 'INPUT', 'TEXTAREA'];
function parseNode(node) {
    if (!node?.parentElement?.tagName || IGNORE_NODE_TAGS.includes(node.parentElement.tagName)) {
        return;
    }
    
    if (node.nodeType === Node.TEXT_NODE && node.nodeValue.length) {
        try {
            const brSpan = highlightText(node.nodeValue);
            if (brSpan === null) return;
            node.parentElement.replaceChild(brSpan, node);
        } catch (e) {
            console.error('Error parsing text node:', e);
        }
        return;
    }
    
    if (node.hasChildNodes()) [...node.childNodes].forEach(parseNode);
}

function setBold(enabled) {
    if (enabled) {
        document.body.setAttribute("br-mode", "on");
        [...document.body.childNodes].forEach(parseNode);
    } else {
        document.body.setAttribute("br-mode", "off");
    }
}

${if (boldCharacters) "setBold(true);" else ""}

var images = document.querySelectorAll("img");

images.forEach(function(img) {
    img.onload = function() {
        img.classList.add("loaded");
        console.log("Image width:", img.width, "px");
        if (img.width < 412) {
            img.classList.add("thin");
        }
    };

    img.onerror = function() {
        console.error("Failed to load image:", img.src);
    };
});
"""
}
