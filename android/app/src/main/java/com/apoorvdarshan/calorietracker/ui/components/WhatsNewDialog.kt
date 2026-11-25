package com.apoorvdarshan.calorietracker.ui.components

import android.content.Intent
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.models.FudAILinks
import com.apoorvdarshan.calorietracker.services.hosted.HostedPrompt
import com.apoorvdarshan.calorietracker.services.hosted.PurchaseOutcome
import com.apoorvdarshan.calorietracker.services.update.AndroidUpdateChecker
import com.apoorvdarshan.calorietracker.services.voice.LiveProvider
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import com.apoorvdarshan.calorietracker.services.voice.VoiceAccess
import com.apoorvdarshan.calorietracker.services.voice.VoiceProviderResolver
import com.apoorvdarshan.calorietracker.ui.hosted.findActivity
import com.apoorvdarshan.calorietracker.ui.theme.AppColors
import com.apoorvdarshan.calorietracker.ui.voice.LiveOrb
import com.revenuecat.purchases.models.StoreProduct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** One page of the Live Voice "What's new" pop-up (iOS WhatsNewSheet parity). */
enum class WhatsNewPage { LIVE_VOICE, ACCESS, SUPPORT, DEVELOPER }

/** What the access page can offer, from the subscription and the saved keys. */
data class WhatsNewAccess(
    /** Plus or Pro can be bought now: not subscribed, and the plans loaded. */
    val canBuyPlans: Boolean,
    /** Live Voice can't start yet: no plan and no usable own key. */
    val needsLiveVoiceSetup: Boolean
)

object WhatsNewPages {
    /**
     * @param access null when the subscription couldn't be checked. The access page is then
     *   left out, so a subscriber is never asked to subscribe.
     */
    fun pages(access: WhatsNewAccess?): List<WhatsNewPage> = buildList {
        add(WhatsNewPage.LIVE_VOICE)
        if (access != null && (access.canBuyPlans || access.needsLiveVoiceSetup)) add(WhatsNewPage.ACCESS)
        add(WhatsNewPage.SUPPORT)
        add(WhatsNewPage.DEVELOPER)
    }
}

/**
 * Checks the subscription, the plans and the saved keys. Gives up after a few seconds so an
 * offline launch still shows the pop-up, just without the access page; the check keeps running
 * on its own scope so a billing call that ignores cancellation can't hold the pop-up back.
 */
suspend fun loadWhatsNewAccess(container: AppContainer): WhatsNewAccess? {
    val check = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).async {
        val billing = container.billing
        if (billing.isAvailable) {
            // A failed refresh says nothing about the subscription.
            if (!billing.refreshCustomerInfo()) return@async null
            if (billing.entitlement.value.isEntitled) return@async WhatsNewAccess(canBuyPlans = false, needsLiveVoiceSetup = false)
            billing.loadOfferings()
        }
        val voicePrefs = container.prefs.voiceAssistantPreferences.first()
        val selectedAi = container.prefs.selectedAIProvider.first()
        val keys = withContext(Dispatchers.IO) {
            LiveProvider.ownKeyProviders.associateWith { provider ->
                container.keyStore.liveApiKey(provider)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        val access = VoiceProviderResolver.resolve(
            voicePrefs.choice, selectedAi, { keys[it] != null }, billing.entitlement.value.isEntitled,
            qwenWorkspaceConfigured = com.apoorvdarshan.calorietracker.services.voice.QwenRealtimeWire
                .isValidWorkspace(voicePrefs.qwenWorkspace)
        )
        WhatsNewAccess(
            canBuyPlans = billing.isAvailable && billing.hasPurchasablePlans,
            needsLiveVoiceSetup = access !is VoiceAccess.Ready
        )
    }
    return withTimeoutOrNull(6_000) { check.await() }
}

/**
 * The one-time pop-up existing users see after the Live Voice update. Back and taps outside
 * are ignored and only Close on the last page (or Add an API Key) finishes it, so a killed app
 * shows it again on the next launch.
 */
@Composable
fun WhatsNewDialog(
    container: AppContainer,
    access: WhatsNewAccess?,
    onClose: () -> Unit,
    onAddKey: () -> Unit
) {
    val pages = remember(access) { WhatsNewPages.pages(access) }
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    // The demo starts with sound unless the phone is on silent or vibrate.
    val context = LocalContext.current
    var muted by remember {
        mutableStateOf(context.getSystemService(AudioManager::class.java)?.ringerMode != AudioManager.RINGER_MODE_NORMAL)
    }
    val current = pagerState.currentPage
    val isLast = current >= pages.lastIndex

    LaunchedEffect(current) {
        if (current != 0) muted = true
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        FudGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.94f)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp),
            cornerRadius = 28.dp,
            padding = 0.dp
        ) {
            Column(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { index ->
                    when (pages[index]) {
                        WhatsNewPage.LIVE_VOICE -> LiveVoicePage(
                            isCurrent = current == index,
                            muted = muted,
                            onToggleMute = { muted = !muted }
                        )
                        WhatsNewPage.ACCESS -> if (access != null) {
                            AccessPage(
                                access = access,
                                onPlans = { container.hostedUi.show(HostedPrompt.Paywall) },
                                onAddKey = onAddKey
                            )
                        }
                        WhatsNewPage.SUPPORT -> SupportPage(container)
                        WhatsNewPage.DEVELOPER -> DeveloperPage()
                    }
                }
                PageDots(count = pages.size, current = current)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (current > 0) {
                        SecondaryButton(
                            text = stringResource(R.string.whats_new_previous),
                            onClick = { scope.launch { pagerState.animateScrollToPage(current - 1) } },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    FudGlassPrimaryButton(
                        text = stringResource(if (isLast) R.string.cd_close else R.string.whats_new_next),
                        onClick = {
                            if (isLast) onClose() else scope.launch { pagerState.animateScrollToPage(current + 1) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// -- Page 1: Live Voice ------------------------------------------------------

@Composable
private fun LiveVoicePage(isCurrent: Boolean, muted: Boolean, onToggleMute: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val videoDescription = stringResource(R.string.whats_new_video_cd)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val videoHeight = (maxHeight - 250.dp).coerceIn(200.dp, 540.dp)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .height(videoHeight)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black)
            ) {
                LoopingRawVideo(
                    resId = R.raw.live_voice_whats_new,
                    muted = muted,
                    playing = isCurrent && resumed,
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics { contentDescription = videoDescription }
                )
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(onClick = onToggleMute),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(if (muted) R.string.whats_new_sound_on else R.string.whats_new_sound_off),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            PageTitle(stringResource(R.string.whats_new_title))
            PageBody(stringResource(R.string.whats_new_body))
            Text(
                stringResource(R.string.whats_new_tap_orb),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                stringResource(R.string.whats_new_access_line),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

private val VIDEO_AUDIO: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
    .build()

/**
 * A looping video from res/raw on a TextureView, so rounded corners clip it. Muted it never
 * takes audio focus, so the user's music keeps playing; with sound on it asks for focus.
 */
@Composable
private fun LoopingRawVideo(@RawRes resId: Int, muted: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember { MediaPlayer() }
    var prepared by remember { mutableStateOf(false) }
    var hasSource by remember { mutableStateOf(false) }
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val focusRequest = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(VIDEO_AUDIO)
            .build()
    }

    DisposableEffect(Unit) {
        onDispose {
            audioManager?.abandonAudioFocusRequest(focusRequest)
            runCatching { player.release() }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        runCatching {
                            player.setSurface(Surface(texture))
                            if (!hasSource) {
                                hasSource = true
                                ctx.resources.openRawResourceFd(resId).use { fd ->
                                    player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                                }
                                player.setAudioAttributes(VIDEO_AUDIO)
                                player.isLooping = true
                                player.setVolume(0f, 0f)
                                player.setOnPreparedListener { prepared = true }
                                player.prepareAsync()
                            }
                        }
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        runCatching { player.setSurface(null) }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        }
    )

    LaunchedEffect(prepared, playing, muted) {
        if (!prepared) return@LaunchedEffect
        val volume = if (muted) 0f else 1f
        runCatching { player.setVolume(volume, volume) }
        runCatching {
            if (playing) player.start() else if (player.isPlaying) player.pause()
        }
        // Hold focus only while sound is actually playing, so a paused or muted demo never
        // keeps the user's music stopped.
        if (playing && !muted) {
            audioManager?.requestAudioFocus(focusRequest)
        } else {
            audioManager?.abandonAudioFocusRequest(focusRequest)
        }
    }
}

// -- Page 2: getting Live Voice ----------------------------------------------

@Composable
private fun AccessPage(access: WhatsNewAccess, onPlans: () -> Unit, onAddKey: () -> Unit) {
    PageColumn {
        LiveOrb(
            mode = OrbMode.LISTENING,
            tint = AppColors.Calorie,
            modifier = Modifier.size(180.dp),
            clearBackground = true
        )
        PageTitle(
            stringResource(
                if (access.needsLiveVoiceSetup) R.string.whats_new_access_title_setup else R.string.whats_new_access_title_plans
            )
        )
        PageBody(
            stringResource(
                when {
                    !access.needsLiveVoiceSetup -> R.string.whats_new_access_body_plans
                    access.canBuyPlans -> R.string.whats_new_access_body_setup
                    else -> R.string.whats_new_access_body_key_only
                }
            )
        )
        Spacer(Modifier.height(4.dp))
        // Only plans that can actually be bought; otherwise the paywall would be empty.
        if (access.canBuyPlans) {
            FudGlassPrimaryButton(text = "", onClick = onPlans) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.hosted_upsell_cta), color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        if (access.needsLiveVoiceSetup) {
            if (access.canBuyPlans) {
                SecondaryButton(
                    text = stringResource(R.string.voice_action_add_key),
                    onClick = onAddKey,
                    icon = { Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                FudGlassPrimaryButton(text = stringResource(R.string.voice_action_add_key), onClick = onAddKey)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                for (logo in PROVIDER_LOGOS) {
                    Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

private val PROVIDER_LOGOS = listOf(
    R.drawable.provider_gemini,
    R.drawable.provider_openai,
    R.drawable.provider_xai,
    R.drawable.provider_qwen
)

// -- Page 3: support -----------------------------------------------------------

@Composable
private fun SupportPage(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val billing = container.billing
    var products by remember { mutableStateOf<Map<String, StoreProduct>?>(null) }
    var purchasingId by remember { mutableStateOf<String?>(null) }
    var thanked by remember { mutableStateOf(false) }
    val shareText = stringResource(R.string.about_share_message)
    val shareChooser = stringResource(R.string.about_share_chooser)

    LaunchedEffect(Unit) {
        products = if (billing.isAvailable) {
            billing.loadProducts(TIP_TIERS.map { it.productId }).associateBy { it.id }
        } else {
            emptyMap()
        }
    }

    PageColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (tier in TIP_TIERS) {
                Image(
                    bitmap = ImageBitmap.imageResource(tier.icon),
                    contentDescription = null,
                    filterQuality = FilterQuality.None,
                    modifier = Modifier.size(44.dp)
                )
            }
        }
        PageTitle(stringResource(R.string.about_category_support))
        PageBody(stringResource(R.string.whats_new_support_body))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                text = stringResource(R.string.about_rate),
                onClick = { AndroidUpdateChecker.openPlayStore(context) },
                icon = { Icon(Icons.Filled.Star, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
            SecondaryButton(
                text = stringResource(R.string.about_share),
                onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            },
                            shareChooser
                        )
                    )
                },
                icon = { Icon(Icons.Filled.Share, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(18.dp)) },
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            stringResource(R.string.tip_jar_title).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .semantics { heading() }
        )
        for (row in TIP_TIERS.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (tier in row) {
                    val product = products?.get(tier.productId)
                    TipButton(
                        tier = tier,
                        price = product?.price?.formatted,
                        loading = products == null || purchasingId == tier.productId,
                        enabled = product != null && purchasingId == null,
                        modifier = Modifier.weight(1f)
                    ) {
                        val activity = context.findActivity()
                        if (product == null || activity == null || purchasingId != null) return@TipButton
                        purchasingId = tier.productId
                        scope.launch {
                            if (billing.purchaseProduct(activity, product) == PurchaseOutcome.Success) thanked = true
                            purchasingId = null
                        }
                    }
                }
            }
        }
    }

    if (thanked) {
        FudGlassDialog(onDismissRequest = { thanked = false }) {
            Text(stringResource(R.string.tip_thanks_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.tip_thanks_body), style = MaterialTheme.typography.bodyMedium)
            FudGlassDialogActions(primaryText = stringResource(R.string.action_done), onPrimary = { thanked = false })
        }
    }
}

@Composable
private fun TipButton(
    tier: TipTier,
    price: String?,
    loading: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Image(
            bitmap = ImageBitmap.imageResource(tier.icon),
            contentDescription = null,
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(30.dp)
        )
        Text(stringResource(tier.nameRes), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.Calorie)
                price != null -> Text(price, color = AppColors.Calorie, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                else -> Icon(
                    Icons.Filled.CloudOff,
                    contentDescription = stringResource(R.string.tip_unavailable),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// -- Page 4: the developer and the community ---------------------------------

@Composable
private fun DeveloperPage() {
    val context = LocalContext.current
    fun open(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    PageColumn {
        Image(
            painterResource(R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        Text(
            stringResource(R.string.post_update_meet_dev_title).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = AppColors.Calorie
        )
        PageTitle(stringResource(R.string.post_update_meet_dev_greeting))
        PageBody(stringResource(R.string.post_update_meet_dev_body))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        ) {
            SocialRow(Icons.Filled.AlternateEmail, stringResource(R.string.about_follow_x), "@apoorvdarshan") { open(FudAILinks.X) }
            Box(
                Modifier
                    .padding(start = 52.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            )
            SocialRow(Icons.Filled.CameraAlt, stringResource(R.string.about_follow_instagram), "@apoorvcodes") { open(FudAILinks.INSTAGRAM) }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Forum, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.whats_new_community_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                stringResource(R.string.whats_new_community_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            FudGlassPrimaryButton(text = stringResource(R.string.about_join_discord), onClick = { open(FudAILinks.DISCORD) })
        }
    }
}

@Composable
private fun SocialRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, handle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(handle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        Icon(
            Icons.Filled.NorthEast,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.size(16.dp)
        )
    }
}

// -- Shared pieces ---------------------------------------------------------------

/** Centres a page vertically and scrolls when it doesn't fit (small phones, large text). */
@Composable
private fun PageColumn(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
        ) {
            content()
        }
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() }
    )
}

@Composable
private fun PageBody(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun PageDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)
    ) {
        repeat(count) { index ->
            Box(
                Modifier
                    .height(7.dp)
                    .width(if (index == current) 20.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        if (index == current) AppColors.Calorie else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
            )
        }
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            icon()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
