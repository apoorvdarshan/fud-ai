package com.apoorvdarshan.calorietracker.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarRate
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

/**
 * Verbatim port of struct AboutView in
 * ios/calorietracker/ContentView.swift.
 *
 * Two grouped sections + footer:
 *  Section 1 (10 rows): Rate / Share / Open Source / Star / Vote on PH /
 *    Support / Report Issue / Request Feature / Contact / Follow on X
 *  Section 2 (2 rows): Privacy Policy / Terms of Service
 *  Footer: 'Made by Apoorv Darshan' / 'with care, for everyone'
 *
 * iOS uses .listRowBackground(AppColors.appCard) so each row sits on the
 * card surface; icons are pink (Calorie); labels use .primary text color.
 * Compose grouped cards with hairline dividers reproduce the visual.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(container: AppContainer) {
    val ctx = LocalContext.current

    fun open(url: String) =
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    fun share() {
        val text = "I've been tracking my meals with Fud AI — snap a photo, speak it, or type it, and the AI logs the calories. It's free, open source, and your data stays on your device.\n\nDownload: https://fud-ai.app"
        ctx.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            },
            "Share Fud AI"
        ))
    }

    fun rate() {
        val market = Uri.parse("market://details?id=${ctx.packageName}")
        runCatching {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, market).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                }
            )
        }.onFailure {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ctx.packageName}")))
        }
    }

    fun email() = ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:apoorv@fud-ai.app")))

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Section 1 — actions + community (10 rows in iOS order)
            item {
                CardSection {
                    // Rate the App — hidden until Fud AI ships on Play Store.
                    // AboutRow(Icons.Filled.Star, "Rate the App", onClick = ::rate)
                    // Hairline()
                    AboutRow(Icons.Filled.Share, "Share the App", onClick = ::share)
                    Hairline()
                    AboutRow(Icons.Filled.Code, "Open Source (MIT)") { open("https://github.com/apoorvdarshan/fud-ai") }
                    Hairline()
                    AboutRow(Icons.Filled.StarRate, "Star on GitHub") { open("https://github.com/apoorvdarshan/fud-ai") }
                    Hairline()
                    AboutRow(Icons.Filled.ThumbUp, "Vote on Product Hunt") { open("https://www.producthunt.com/products/fud-ai-calorie-tracker") }
                    Hairline()
                    AboutRow(Icons.Filled.Favorite, "Support the Project") { open("https://paypal.me/apoorvdarshan") }
                    Hairline()
                    AboutRow(Icons.Filled.BugReport, "Report an Issue") { open("https://github.com/apoorvdarshan/fud-ai/issues/new?labels=bug&title=Bug:%20") }
                    Hairline()
                    AboutRow(Icons.Filled.Lightbulb, "Request a Feature") { open("https://github.com/apoorvdarshan/fud-ai/issues/new?labels=enhancement&title=Feature:%20") }
                    Hairline()
                    AboutRow(Icons.Filled.Email, "Contact Us", onClick = ::email)
                    Hairline()
                    AboutRow(Icons.Filled.AlternateEmail, "Follow on X") { open("https://x.com/apoorvdarshan") }
                }
            }

            // Section 2 — legal
            item {
                CardSection {
                    AboutRow(Icons.Filled.Lock, "Privacy Policy") { open("https://fud-ai.app/privacy.html") }
                    Hairline()
                    AboutRow(Icons.Filled.Description, "Terms of Service") { open("https://fud-ai.app/terms.html") }
                }
            }

            // Footer
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Made by Apoorv Darshan",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    Text(
                        "with care, for everyone",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
private fun CardSection(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) { content() }
}

@Composable
private fun AboutRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.Calorie, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .padding(start = 54.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    )
}
