package me.ash.reader.ui.page.settings.tips

import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ReportGmailerrorred
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.graphics.shapes.Morph
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import me.ash.reader.R
import me.ash.reader.infrastructure.preference.OpenLinkPreference
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.OrigReadScaffold
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.getCurrentVersion
import me.ash.reader.ui.ext.openURL
import me.ash.reader.ui.ext.put
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.graphics.MorphPolygonShape
import me.ash.reader.ui.page.settings.RuleMarkdownGuideDialog
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.theme.palette.onLight

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private val ShapeGacha by lazy {
    buildList {
        MaterialShapes.run {
            add(Cookie12Sided)
            add(Cookie4Sided)
            add(Cookie6Sided)
            add(Cookie7Sided)
            add(Cookie9Sided)
            add(Clover8Leaf)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TipsAndSupportPage(
    updateViewModel: UpdateViewModel = hiltViewModel(),
    onBack: () -> Unit,
    navigateToLicenseList: () -> Unit,
    navigateToTroubleshooting: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var currentVersion by remember { mutableStateOf("") }
    var userGuideVisible by remember { mutableStateOf(false) }
    val troubleshootingEnabled =
        context.dataStore.data.map { preferences ->
            preferences[booleanPreferencesKey(DataStoreKey.troubleshootingEnabled)] ?: false
        }.collectAsStateValue(false)

    val morphProgress = remember { Animatable(0f) }

    val polygonShape = remember { ShapeGacha.random() }
    val circle = MaterialShapes.Circle
    val morph = Morph(polygonShape, circle)

    val shadowShape by remember {
        derivedStateOf {
            MorphPolygonShape(morph, morphProgress.value)
        }
    }

    val bgShape by remember {
        derivedStateOf {
            MorphPolygonShape(morph, morphProgress.value)
        }
    }

    val morphSpec = MaterialTheme.motionScheme.slowEffectsSpec<Float>()
    val colorScheme = MaterialTheme.colorScheme

    val colorGacha = remember {
        listOf(
            colorScheme.primaryFixed,
            colorScheme.secondaryFixed,
            colorScheme.tertiaryFixed
        )
    }

    val logoBGColor = remember { colorGacha.random() }


    LaunchedEffect(Unit) {
        currentVersion = context.getCurrentVersion().toString()
    }

    OrigReadScaffold(
        containerColor = MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack
            )
        },
        actions = {
            FeedbackIconButton(
                modifier = Modifier.size(22.dp),
                imageVector = Icons.Outlined.BugReport,
                contentDescription = stringResource(
                    if (troubleshootingEnabled) R.string.troubleshooting_hide
                    else R.string.troubleshooting_show
                ),
                tint = if (troubleshootingEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                onClick = {
                    scope.launch {
                        context.dataStore.put(
                            DataStoreKey.troubleshootingEnabled,
                            !troubleshootingEnabled,
                        )
                    }
                },
            )
            FeedbackIconButton(
                modifier = Modifier.size(20.dp),
                imageVector = Icons.Rounded.Balance,
                contentDescription = stringResource(R.string.open_source_licenses),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = navigateToLicenseList
            )
        },
        content = {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                item {
                    Column(
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    scope.launch { morphProgress.animateTo(1f, morphSpec) }
                                    tryAwaitRelease()
                                    scope.launch { morphProgress.animateTo(0f, morphSpec) }
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    view.playSoundEffect(SoundEffectConstants.CLICK)
                                },
                                onTap = {
                                    updateViewModel.checkUpdate(
                                        {
                                            context.showToast(context.getString(R.string.checking_updates))
                                            context.dataStore.put(
                                                DataStoreKey.skipVersionNumber,
                                                ""
                                            )
                                        },
                                        {
                                            if (!it) {
                                                context.showToast(
                                                    context.getString(R.string.is_latest_version)
                                                )
                                            }
                                        }
                                    )
                                }
                            )
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(204.dp)
                                .background(color = logoBGColor, shape = bgShape)
                                .dropShadow(
                                    shape = shadowShape,
                                    Shadow(
                                        radius = 20.dp,
                                        spread = 12.dp,
                                        alpha = .1f,
                                        color = logoBGColor
                                    )
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                modifier = Modifier.size(136.dp),
                                painter = painterResource(R.drawable.origread_logo),
                                contentDescription = stringResource(R.string.origread),
                            )
                        }
                        Spacer(modifier = Modifier.height(28.dp))
                        Text(
                            text = stringResource(R.string.origread),
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "OrigRead",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.origread_tagline),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        ) {
                            Text(
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                                text = "v$currentVersion",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        SettingItem(
                            title = stringResource(R.string.user_guide_title),
                            desc = stringResource(R.string.user_guide_desc),
                            icon = Icons.Outlined.Description,
                            onClick = { userGuideVisible = true },
                            action = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = stringResource(R.string.go_to),
                                )
                            },
                        )
                        if (troubleshootingEnabled) {
                            Spacer(modifier = Modifier.height(16.dp))
                            SettingItem(
                                title = stringResource(R.string.troubleshooting),
                                desc = stringResource(R.string.troubleshooting_desc),
                                icon = Icons.Outlined.ReportGmailerrorred,
                                onClick = navigateToTroubleshooting,
                                action = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                        contentDescription = stringResource(R.string.go_to),
                                    )
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.origread_multiplatform_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            text = stringResource(R.string.origread_multiplatform_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlatformProjectButton(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.PhoneAndroid,
                                label = stringResource(R.string.origread_android_project),
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    view.playSoundEffect(SoundEffectConstants.CLICK)
                                    context.openURL(
                                        context.getString(R.string.github_link),
                                        OpenLinkPreference.AutoPreferCustomTabs
                                    )
                                },
                            )
                            PlatformProjectButton(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Rounded.Computer,
                                label = stringResource(R.string.origread_desktop_project),
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    view.playSoundEffect(SoundEffectConstants.CLICK)
                                    context.openURL(
                                        context.getString(R.string.desktop_github_link),
                                        OpenLinkPreference.AutoPreferCustomTabs
                                    )
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = stringResource(R.string.origread_open_source_footer),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    )

    if (userGuideVisible) {
        RuleMarkdownGuideDialog(
            title = stringResource(R.string.user_guide_title),
            assetName = "user-guide",
            onDismiss = { userGuideVisible = false },
        )
    }

    UpdateDialog()
}

@Composable
private fun PlatformProjectButton(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        modifier = Modifier.size(21.dp),
                        imageVector = icon,
                        contentDescription = null,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(R.string.origread_github_repository),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                modifier = Modifier.size(18.dp),
                painter = painterResource(R.drawable.ic_github),
                contentDescription = "$label GitHub",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
