package com.mifare.cloner.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import com.mifare.cloner.data.CardScheme
import com.mifare.cloner.data.EmvCard
import com.mifare.cloner.data.MifareDump
import com.mifare.cloner.data.ScanMode
import com.mifare.cloner.data.TransportCard
import com.mifare.cloner.data.TransportCardType
import com.mifare.cloner.nfc.ClonerOperationState
import com.mifare.cloner.ui.components.RadarAnimation
import com.mifare.cloner.viewmodel.MainViewModel

@Composable
fun ScanScreen(viewModel: MainViewModel, state: ClonerOperationState) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        item {
            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                label = "scan_state_anim"
            ) { targetState ->
                when (targetState) {
                    is ClonerOperationState.IdleWaitOriginal -> {
                        IdleWaitOriginalView(
                            scanMode = viewModel.uiState.value.settings.scanMode,
                            onOpenSettings = { viewModel.selectTab(2) }
                        )
                    }

                    is ClonerOperationState.ReadingOriginal -> {
                        ReadingOriginalView(message = targetState.message)
                    }

                    is ClonerOperationState.ReadyToWrite -> {
                        ReadyToWriteView(
                            dump = targetState.sourceDump,
                            onCancel = { viewModel.resetToOriginalScan() }
                        )
                    }

                    is ClonerOperationState.WritingGen2 -> {
                        WritingGen2View(message = targetState.message)
                    }

                    is ClonerOperationState.WriteSuccess -> {
                        WriteSuccessView(
                            dump = targetState.dump,
                            onNewScan = { viewModel.resetToOriginalScan() }
                        )
                    }

                    is ClonerOperationState.EmvCardScanned -> {
                        EmvCardScannedView(
                            card = targetState.card,
                            onNewScan = { viewModel.resetToOriginalScan() }
                        )
                    }

                    is ClonerOperationState.TransportCardScanned -> {
                        TransportCardScannedView(
                            card = targetState.card,
                            onNewScan = { viewModel.resetToOriginalScan() }
                        )
                    }

                    is ClonerOperationState.OperationError -> {
                        OperationErrorView(
                            errorState = targetState,
                            onRetry = { viewModel.retryOperation(targetState) },
                            onNewScan = { viewModel.resetToOriginalScan() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IdleWaitOriginalView(
    scanMode: ScanMode,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            onClick = onOpenSettings,
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = scanMode.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        RadarAnimation(size = 230.dp) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(46.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        Text(
            text = "приложите оригинал mifare classic",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReadingOriginalView(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(72.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 5.dp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "подбор ключей и считывание секторов...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ReadyToWriteView(
    dump: MifareDump,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "оригинал считан",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = dump.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "UID: ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = dump.formattedUid,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "режим: ${dump.modeDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        RadarAnimation(size = 210.dp, color = MaterialTheme.colorScheme.secondary) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "дамп готов. приложите заготовку gen 2",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("отмена / новый скан", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WritingGen2View(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(72.dp),
            color = MaterialTheme.colorScheme.secondary,
            strokeWidth = 5.dp
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "запись нулевого блока и данных... не убирайте метку!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WriteSuccessView(
    dump: MifareDump,
    onNewScan: () -> Unit
) {
    LaunchedEffect(dump) {
        kotlinx.coroutines.delay(3000L)
        onNewScan()
    }

    val scale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "success_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Color(0xFF00C853)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(56.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "успешно скопировано!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF00C853),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "UID и сектора полностью перенесены на заготовку Gen 2 (CUID)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = dump.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "клонированный UID: ${dump.formattedUid}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onNewScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "новый скан",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun OperationErrorView(
    errorState: ClonerOperationState.OperationError,
    onRetry: () -> Unit,
    onNewScan: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = if (errorState.isWriteError) "ошибка записи на gen 2" else "ошибка чтения",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
            )
        ) {
            Text(
                text = errorState.error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp)
            )
        }

        Spacer(modifier = Modifier.height(28.dp))

        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (errorState.isWriteError) "повторить запись на gen 2" else "попробовать снова",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        if (errorState.isWriteError) {
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onNewScan,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("сканировать другой оригинал", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun EmvCardScannedView(
    card: EmvCard,
    onNewScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "банковская карта",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Virtual Bank Card widget (Aspect Ratio 1.586: 85.6mm x 53.98mm)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.586f)
                .clip(RoundedCornerShape(18.dp))
                .border(
                    width = 1.5.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(18.dp)
                )
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(1000f, 600f)
                    )
                )
                .padding(20.dp)
        ) {
            // Subtle internal glowing radial highlight
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x3380D4FF),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.85f, size.height * 0.2f),
                        radius = size.width * 0.6f
                    )
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row: Contactless icon + Scheme Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Nfc,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )

                    EmvSchemeBadge(scheme = card.scheme)
                }

                // Middle: EMV Chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EmvChipWidget()
                }

                // PAN (Card Number)
                Text(
                    text = card.formattedPan,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        fontSize = 19.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )

                // Bottom Row: Holder / Application Label & Expiry Date
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = card.cardholderName ?: card.applicationLabel ?: card.scheme.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "VALID THRU",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = card.formattedExpiry,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Technical details card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (!card.aid.isNullOrEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "AID приложения:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = card.aid,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (!card.applicationLabel.isNullOrEmpty()) {
                    if (!card.aid.isNullOrEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "метка приложения:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = card.applicationLabel,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Close / New Scan Button
        Button(
            onClick = onNewScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "закрыть / новый скан",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun EmvChipWidget() {
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFFFD54F),
                        Color(0xFFFFB300),
                        Color(0xFFFF8F00)
                    )
                )
            )
            .border(0.5.dp, Color(0xFFFFA000), RoundedCornerShape(6.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val stroke = Stroke(width = 1.dp.toPx())
            val lineColor = Color(0x77000000)

            // Chip contact lines
            drawRoundRect(
                color = lineColor,
                topLeft = Offset(w * 0.28f, h * 0.22f),
                size = Size(w * 0.44f, h * 0.56f),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                style = stroke
            )
            drawLine(lineColor, Offset(0f, h * 0.5f), Offset(w * 0.28f, h * 0.5f), strokeWidth = 1.dp.toPx())
            drawLine(lineColor, Offset(w * 0.72f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = 1.dp.toPx())
            drawLine(lineColor, Offset(w * 0.5f, 0f), Offset(w * 0.5f, h * 0.22f), strokeWidth = 1.dp.toPx())
            drawLine(lineColor, Offset(w * 0.5f, h * 0.78f), Offset(w * 0.5f, h), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun EmvSchemeBadge(scheme: CardScheme) {
    when (scheme) {
        CardScheme.MIR -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF00A054))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "МИР",
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    color = Color.White
                )
            }
        }
        CardScheme.VISA -> {
            Text(
                text = "VISA",
                fontWeight = FontWeight.Black,
                fontStyle = FontStyle.Italic,
                fontSize = 18.sp,
                color = Color(0xFFF7B600),
                letterSpacing = 1.sp
            )
        }
        CardScheme.MASTERCARD -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFEB001B))
                )
                Box(
                    modifier = Modifier
                        .offset(x = (-6).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF79E1B).copy(alpha = 0.9f))
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = "mastercard",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.White
                )
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = scheme.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun TransportCardScannedView(
    card: TransportCard,
    onNewScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "транспортная карта",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Virtual Transport Card widget (Aspect Ratio 1.586: ID-1 format)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.586f)
                .clip(RoundedCornerShape(18.dp))
                .border(
                    width = 1.5.dp,
                    color = card.type.primaryGradient[0].copy(alpha = 0.8f),
                    shape = RoundedCornerShape(18.dp)
                )
                .background(
                    brush = Brush.linearGradient(
                        colors = card.type.primaryGradient,
                        start = Offset(0f, 0f),
                        end = Offset(1000f, 600f)
                    )
                )
                .padding(20.dp)
        ) {
            // Subtle internal glowing radial highlight
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0x33FFFFFF),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.85f, size.height * 0.2f),
                        radius = size.width * 0.6f
                    )
                )
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Row: NFC Icon + Transport Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Nfc,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(24.dp)
                    )

                    TransportCardBadge(type = card.type)
                }

                // Middle: Large Balance in rubles
                Column {
                    Text(
                        text = "баланс",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.75f),
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = card.formattedBalance,
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp
                        ),
                        color = Color.White
                    )
                }

                // Bottom Row: Card Number & UID
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "номер карты",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.7f),
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = card.formattedCardNumber,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            ),
                            color = Color.White
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "UID",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.7f),
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = card.formattedUid,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Technical details card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "система:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = card.type.systemName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "регион:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = card.type.city,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (card.sectorsRead.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "считанные сектора:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = card.sectorsRead.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Close / New Scan Button
        Button(
            onClick = onNewScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "закрыть / новый скан",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }
    }
}

@Composable
private fun TransportCardBadge(type: TransportCardType) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.2f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = type.title,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = Color.White
        )
    }
}
