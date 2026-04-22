package com.apoorvdarshan.calorietracker.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apoorvdarshan.calorietracker.AppContainer
import com.apoorvdarshan.calorietracker.R
import com.apoorvdarshan.calorietracker.ui.theme.AppColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(container: AppContainer) {
    val ctx = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("About", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Hero
            Column(
                Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_logo),
                    contentDescription = "Fud AI",
                    modifier = Modifier.size(96.dp).clip(CircleShape)
                )
                Spacer(Modifier.height(12.dp))
                Text("Fud AI", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Version 1.0",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Open source · Free forever",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppColors.Calorie
                )
            }

            SectionCard {
                AboutRow(
                    icon = Icons.Filled.Share,
                    tint = AppColors.Calorie,
                    label = "Share the app"
                ) {
                    val text = "Fud AI — free open source calorie tracker. Bring your own API key, no subscription. https://fud-ai.app"
                    ctx.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        },
                        "Share Fud AI"
                    ))
                }
                RowDivider()
                AboutRow(
                    icon = Icons.Filled.Star,
                    tint = Color(0xFFFF9500),
                    label = "Rate on Play Store"
                ) {
                    val uri = Uri.parse("market://details?id=${ctx.packageName}")
                    runCatching {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        })
                    }.onFailure {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${ctx.packageName}")))
                    }
                }
                RowDivider()
                AboutRow(
                    icon = Icons.Filled.Code,
                    tint = Color(0xFF5856D6),
                    label = "Source on GitHub"
                ) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/apoorvdarshan/fud-ai"))) }
            }

            SectionCard {
                AboutRow(
                    icon = Icons.Filled.Mail,
                    tint = Color(0xFF007AFF),
                    label = "Contact"
                ) {
                    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:apoorv@fud-ai.app")))
                }
                RowDivider()
                AboutRow(
                    icon = Icons.Filled.Favorite,
                    tint = AppColors.Calorie,
                    label = "Donate"
                ) {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/apoorvdarshan")))
                }
            }
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) { content() }
}

@Composable
private fun AboutRow(icon: ImageVector, tint: Color, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(tint.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .padding(start = 58.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    )
}
