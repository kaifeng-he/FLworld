package com.hkfcl.world

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
internal fun AlbumScreen(
    items: List<AlbumItem>,
    quota: AlbumQuotaState?,
    onBack: () -> Unit,
    onUpload: (String, String, ByteArray, String, String?) -> Unit,
    onDelete: (AlbumItem) -> Unit,
    onRename: (AlbumItem, String) -> Unit,
    onLoadPreview: suspend (String) -> AlbumItem,
    onBackfillPreview: suspend (String, String) -> Unit,
    onLoadItem: suspend (String) -> AlbumItem,
    onError: (String) -> Unit,
    onSaveImage: (AlbumItem) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states = remember { mutableStateMapOf<String, AlbumPreviewLoadState>() }
    val loadingSlots = remember { Semaphore(3) }
    var preview by remember { mutableStateOf<AlbumPreviewLoadState.Ready?>(null) }

    fun preload(item: AlbumItem, retry: Boolean = false) {
        if (item.mediaType != "image" || (!retry && states[item.id] != null)) return
        states[item.id] = AlbumPreviewLoadState.Loading
        scope.launch {
            val result = runCatching {
                loadingSlots.withPermit {
                    val lightweight = runCatching { onLoadPreview(item.id) }.getOrDefault(item)
                    val previewBase64 = lightweight.previewBase64?.takeIf { it.isNotBlank() } ?: run {
                        val full = onLoadItem(item.id)
                        val sourceBytes = full.dataBase64?.let { Base64.decode(it, Base64.DEFAULT) }
                            ?: error("照片内容加载失败")
                        val generated = imagePreviewBase64(sourceBytes) ?: error("不能生成照片预览")
                        runCatching { onBackfillPreview(item.id, generated) }
                        generated
                    }
                    val bitmap = withContext(Dispatchers.Default) {
                        decodeBase64Bitmap(previewBase64) ?: error("不能打开照片预览")
                    }
                    AlbumPreviewLoadState.Ready(item.copy(previewBase64 = previewBase64), bitmap)
                }
            }
            states[item.id] = result.getOrElse {
                onError(it.message ?: "预览加载失败")
                AlbumPreviewLoadState.Failed
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val name = context.albumDisplayName(uri)
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val previewBase64 = if (mimeType.startsWith("image/")) imagePreviewBase64(bytes) else null
            onUpload(name, mimeType, bytes, base64, previewBase64)
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageTitle("相册", onBack, "提前准备好每一张想看的回忆")
        SectionCard(containerColor = Color(0xFFFFF5FB).copy(alpha = 0.93f)) {
            Text("我们的相册", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(albumQuotaText(quota), color = Color(0xFF6F5F66))
            Spacer(Modifier.height(8.dp))
            Button(onClick = { picker.launch(arrayOf("image/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("添加照片或视频") }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { item ->
                LaunchedEffect(item.id) { preload(item) }
                val state = states[item.id]
                AlbumRow(
                    item = item,
                    previewState = state,
                    onPreview = {
                        preview = (state as? AlbumPreviewLoadState.Ready)?.let {
                            it.copy(item = item.copy(previewBase64 = it.item.previewBase64))
                        }
                    },
                    onRename = onRename,
                    onDownload = {
                        scope.launch {
                            runCatching { onLoadItem(item.id) }
                                .onSuccess { onSaveImage(it) }
                                .onFailure { onError(it.message ?: "下载失败") }
                        }
                    },
                    onDelete = onDelete,
                    onRetry = { preload(item, retry = true) }
                )
            }
        }
    }
    preview?.let { ready ->
        AlbumPreviewDialog(ready.item, ready.bitmap) { preview = null }
    }
}

@Composable
private fun AlbumPreviewDialog(item: AlbumItem, bitmap: Bitmap, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.fileName, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "相册照片",
                    modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(18.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(8.dp))
                Text("快速预览；下载时仍会保存原图。", color = Color(0xFF8A747B), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun AlbumRow(
    item: AlbumItem,
    previewState: AlbumPreviewLoadState?,
    onPreview: () -> Unit,
    onRename: (AlbumItem, String) -> Unit,
    onDownload: () -> Unit,
    onDelete: (AlbumItem) -> Unit,
    onRetry: () -> Unit
) {
    var editing by remember(item.id) { mutableStateOf(false) }
    var name by remember(item.fileName) { mutableStateOf(fileBaseName(item.fileName)) }
    val canPreview = item.mediaType == "image" && previewState is AlbumPreviewLoadState.Ready
    SectionCard(Modifier.clickable(enabled = canPreview) { onPreview() }, containerColor = Color(0xFFFFFAFE).copy(alpha = 0.92f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFF4E4F4)), contentAlignment = Alignment.Center) {
                when {
                    item.mediaType == "video" -> Text("视频", color = MaterialTheme.colorScheme.secondary)
                    previewState is AlbumPreviewLoadState.Ready -> Image(
                        bitmap = previewState.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    previewState is AlbumPreviewLoadState.Failed -> Text("重试", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onRetry))
                    else -> CircularProgressIndicator(Modifier.size(23.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (editing) {
                    OutlinedTextField(name, { name = it }, label = { Text("名字") }, suffix = { Text(fileExtension(item.fileName)) }, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(item.fileName, fontWeight = FontWeight.SemiBold)
                    Text("${displayName(item.uploaderId)} · ${sizeText(item.byteSize)}", color = Color(0xFF6F5F66))
                    if (item.mediaType == "image" && previewState !is AlbumPreviewLoadState.Ready) {
                        Text(
                            if (previewState is AlbumPreviewLoadState.Failed) "预览加载失败，点击左侧重试" else "正在准备预览...",
                            color = Color(0xFF9B738C),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (editing) {
                TextButton(onClick = { name.trim().takeIf { it.isNotEmpty() }?.let { onRename(item, it); editing = false } }) { Text("保存") }
                TextButton(onClick = { name = fileBaseName(item.fileName); editing = false }) { Text("取消") }
            } else {
                TextButton(onClick = { editing = true }) { Text("改名") }
                if (item.mediaType == "image") TextButton(onClick = onDownload) { Text("下载") }
                TextButton(onClick = { onDelete(item) }) { Text("删除") }
            }
        }
    }
}

private sealed interface AlbumPreviewLoadState {
    data object Loading : AlbumPreviewLoadState
    data object Failed : AlbumPreviewLoadState
    data class Ready(val item: AlbumItem, val bitmap: Bitmap) : AlbumPreviewLoadState
}

private fun Context.albumDisplayName(uri: Uri): String {
    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
    }
    return "珍贵回忆"
}

private fun fileBaseName(fileName: String): String = fileName.substringBeforeLast('.', fileName)

private fun fileExtension(fileName: String): String {
    val index = fileName.lastIndexOf('.')
    return if (index > 0 && index < fileName.lastIndex) fileName.substring(index) else ""
}

private fun imagePreviewBase64(bytes: ByteArray): String? {
    val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val maxSide = maxOf(source.width, source.height).coerceAtLeast(1)
    val scale = 360f / maxSide
    val preview = if (scale < 1f) {
        Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
    } else {
        source
    }
    val output = ByteArrayOutputStream()
    preview.compress(Bitmap.CompressFormat.JPEG, 45, output)
    if (preview !== source) preview.recycle()
    source.recycle()
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

private fun decodeBase64Bitmap(data: String): Bitmap? {
    val bytes = Base64.decode(data, Base64.DEFAULT)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}
