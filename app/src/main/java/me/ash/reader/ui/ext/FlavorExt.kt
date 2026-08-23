@file:Suppress("SpellCheckingInspection")

package me.ash.reader.ui.ext

import me.ash.reader.BuildConfig

const val GITHUB = "github"
const val FDROID = "fdroid"
const val GOOGLE_PLAY = "googlePlay"
const val STANDARD = "standard"
const val LLM = "llm"

const val isFDroid = BuildConfig.CHANNEL == FDROID
const val isGitHub = BuildConfig.CHANNEL == GITHUB
const val isGooglePlay = BuildConfig.CHANNEL == GOOGLE_PLAY
const val isStandardEdition = BuildConfig.EDITION == STANDARD
const val isLlmEdition = BuildConfig.EDITION == LLM
