package com.example.tuning

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.config.AppConfig
import com.example.statemachine.StateMachineConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.Locale

object StateMachineConfigSerializer {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val smAdapter = moshi.adapter(StateMachineConfig::class.java).indent("  ")
    private val appAdapter = moshi.adapter(AppConfig::class.java)

    fun toJson(config: StateMachineConfig): String {
        return smAdapter.toJson(config)
    }

    /**
     * Parses either a direct StateMachineConfig JSON object (exported by the Web Workbench)
     * or a full AppConfig JSON object containing a nested "stateMachine" property.
     */
    fun fromJson(jsonText: String): StateMachineConfig? {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.contains("\"stateMachine\"")) {
                appAdapter.fromJson(trimmed)?.stateMachine
            } else {
                smAdapter.fromJson(trimmed)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun toKotlinSnippet(cfg: StateMachineConfig): String {
        return String.format(
            Locale.US,
            """
            StateMachineConfig(
                globalMinBrightness = %.2ff,
                globalMaxBrightness = %.2ff,
                brakingIgnoresGlobalMax = %b,
                idleEnterSpeedKmh = %.1ff,
                idleExitSpeedKmh = %.1ff,
                idleEntryFadeS = %.1ff,
                idleBrightness = %.2ff,
                requireBoschIdleConfirm = %b,
                cruisingEntryFadeS = %.1ff,
                cruisingBrightness = %.2ff,
                brakingEntryFadeS = %.1ff,
                brakingDecelThreshold = %.1ff,
                brakingDebounceMs = %dL,
                brakingCooldownMs = %dL,
                brakingMinBrightness = %.2ff,
                brakingMaxBrightness = %.2ff,
                brakingGamma = %.1ff,
                brakingHoldDurationS = %.1ff,
                brakingFadeDurationS = %.1ff,
                useBoschCorroboration = %b
            )
            """.trimIndent(),
            cfg.globalMinBrightness,
            cfg.globalMaxBrightness,
            cfg.brakingIgnoresGlobalMax,
            cfg.idleEnterSpeedKmh,
            cfg.idleExitSpeedKmh,
            cfg.idleEntryFadeS,
            cfg.idleBrightness,
            cfg.requireBoschIdleConfirm,
            cfg.cruisingEntryFadeS,
            cfg.cruisingBrightness,
            cfg.brakingEntryFadeS,
            cfg.brakingDecelThreshold,
            cfg.brakingDebounceMs,
            cfg.brakingCooldownMs,
            cfg.brakingMinBrightness,
            cfg.brakingMaxBrightness,
            cfg.brakingGamma,
            cfg.brakingHoldDurationS,
            cfg.brakingFadeDurationS,
            cfg.useBoschCorroboration
        )
    }
}

@Composable
fun TuningConfigSyncCard(
    config: AppConfig,
    controlsEnabled: Boolean,
    onConfigSave: (AppConfig) -> Unit
) {
    val context = LocalContext.current
    val sm = config.stateMachine

    var showSyncDialog by remember { mutableStateOf(false) }
    var dialogText by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tuning_config_sync_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = "Web Workbench Config Sync",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "Web Workbench Config Sync (JSON / Kotlin)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Round-trip tuned StateMachineConfig thresholds with /tools/telemetry-workbench",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val json = StateMachineConfigSerializer.toJson(sm)
                        copyToClipboard(context, "PrettyNights StateMachineConfig JSON", json)
                        isError = false
                        statusMessage = "Copied StateMachineConfig JSON to clipboard"
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("btn_copy_config_json")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy JSON", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        val kt = StateMachineConfigSerializer.toKotlinSnippet(sm)
                        copyToClipboard(context, "PrettyNights StateMachineConfig Kotlin", kt)
                        isError = false
                        statusMessage = "Copied Kotlin StateMachineConfig(...) snippet"
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("btn_copy_config_kotlin")
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Kotlin", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        dialogText = StateMachineConfigSerializer.toJson(sm)
                        statusMessage = null
                        showSyncDialog = true
                    },
                    enabled = controlsEnabled,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("btn_open_import_json_dialog")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import / Edit JSON", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        val json = StateMachineConfigSerializer.toJson(sm)
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "Pretty Nights StateMachineConfig JSON")
                            putExtra(Intent.EXTRA_TEXT, json)
                        }
                        val chooser = Intent.createChooser(sendIntent, "Share StateMachineConfig JSON")
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(chooser)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("btn_share_config_json")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share JSON", modifier = Modifier.size(16.dp))
                }
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("config_sync_status_text")
                )
            }
        }
    }

    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text("Import / Edit StateMachineConfig JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste the JSON exported from the Pretty Nights Web Telemetry Workbench below to update all 20 state-machine parameters:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    OutlinedTextField(
                        value = dialogText,
                        onValueChange = { dialogText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .testTag("input_import_config_json"),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = StateMachineConfigSerializer.fromJson(dialogText)
                        if (parsed != null) {
                            onConfigSave(config.copy(stateMachine = parsed))
                            isError = false
                            statusMessage = "Applied tuned JSON config from Web Workbench!"
                            showSyncDialog = false
                        } else {
                            isError = true
                            statusMessage = "Invalid StateMachineConfig JSON format"
                        }
                    },
                    modifier = Modifier.testTag("btn_confirm_import_json")
                ) {
                    Text("Apply Tuned Config")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSyncDialog = false },
                    modifier = Modifier.testTag("btn_cancel_import_json")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}
