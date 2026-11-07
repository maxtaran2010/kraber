package app.glagol.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.glagol.ml.AppSettings
import app.glagol.ml.LlmChoice
import app.glagol.ml.LlmModel
import app.glagol.ml.ModelPaths
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: AppSettings, onBack: () -> Unit) {
    val choice by settings.llmChoice.collectAsState()
    val tooHeavy by settings.fullTooHeavy.collectAsState()
    val auto = remember(tooHeavy) { settings.autoModel }
    val ram = settings.totalRam
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeTopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("Конспект и вопросы — локальная LLM") }
            item {
                SettingsCard {
                    Column(Modifier.selectableGroup()) {
                        val autoWhy = when {
                            tooHeavy && auto == LlmModel.LITE -> "1.7B здесь не хватило памяти"
                            ram > 0 -> "ОЗУ устройства ${gb(ram)}"
                            else -> null
                        }
                        ModelOption(
                            selected = choice == LlmChoice.AUTO,
                            title = "Авто",
                            badge = "рекомендуется",
                            subtitle = "Сейчас: ${auto.title}" + (autoWhy?.let { " — $it" } ?: ""),
                            onClick = { settings.setLlmChoice(LlmChoice.AUTO) },
                        )
                        HorizontalDivider(Modifier.padding(start = 64.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ModelOption(
                            selected = choice == LlmChoice.FULL,
                            title = LlmModel.FULL.title,
                            subtitle = "Точнее и подробнее. Нужно от 6 ГБ ОЗУ",
                            onClick = { settings.setLlmChoice(LlmChoice.FULL) },
                        )
                        HorizontalDivider(Modifier.padding(start = 64.dp, end = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        ModelOption(
                            selected = choice == LlmChoice.LITE,
                            title = LlmModel.LITE.title,
                            subtitle = "Легче и быстрее. Хватает 4 ГБ ОЗУ",
                            onClick = { settings.setLlmChoice(LlmChoice.LITE) },
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(
                    choice == LlmChoice.FULL && auto == LlmModel.LITE,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Notice(
                        Icons.Rounded.WarningAmber,
                        "На этом устройстве мало памяти для 1.7B. Если система её выгрузит, конспект доделает 0.6B.",
                        warning = true,
                    )
                }
            }
            item {
                Text(
                    "Обе модели уже внутри приложения — ничего не скачивается. Выбор действует на новые конспекты и вопросы.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            item { Spacer(Modifier.height(8.dp)); SectionTitle("О приложении") }
            item {
                Notice(Icons.Rounded.WifiOff, "Всё работает на телефоне. У приложения нет даже разрешения на доступ в интернет.")
            }
            item {
                SettingsCard {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        InfoRow("Распознавание речи", ModelPaths.ASR_NAME)
                        InfoRow("Поиск речи", ModelPaths.VAD_NAME)
                        InfoRow("Разделение по спикерам", ModelPaths.DIAR_NAME)
                        InfoRow("Конспект и вопросы", "${LlmModel.FULL.title} или ${LlmModel.LITE.title}, int4")
                        InfoRow("Движки", "sherpa-onnx, LiteRT-LM")
                        if (ram > 0) InfoRow("Память устройства", gb(ram))
                        if (version.isNotEmpty()) InfoRow("Версия", version)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

@Composable
private fun ModelOption(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit, badge: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.AutoAwesome, null, Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, warning: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (warning) cs.tertiaryContainer else cs.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (warning) cs.onTertiaryContainer else cs.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (warning) cs.onTertiaryContainer else cs.onSurface,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 4 008 000 000 байт → «3,7 ГБ» (ГиБ, как в настройках Android). */
private fun gb(bytes: Long): String = String.format(Locale.forLanguageTag("ru"), "%.1f ГБ", bytes / (1L shl 30).toDouble())
