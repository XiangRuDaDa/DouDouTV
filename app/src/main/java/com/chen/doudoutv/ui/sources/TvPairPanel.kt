package com.chen.doudoutv.ui.sources

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.chen.doudoutv.data.pair.SourcePairServer
import com.chen.doudoutv.data.pair.localIpAddress
import com.chen.doudoutv.ui.LibraryViewModel
import com.chen.doudoutv.ui.theme.AppColors
import com.chen.doudoutv.ui.theme.Radius
import com.chen.doudoutv.ui.theme.TvText
import com.chen.doudoutv.ui.tv.TvButtonSize
import com.chen.doudoutv.ui.tv.TvPillButton

private const val TAG = "SourcePair"

/**
 * 手机扫码维护 —— 二维码弹层。
 *
 * 服务生命周期就绑在这一个弹层上：打开才监听 10086，关掉（或退出来源页）立刻释放端口。
 * 不在后台挂着一个能往库里写来源的口子，这是这个功能唯一的止血阀。
 *
 * 手机必须和电视在同一个网段，所以把地址也写成文字：扫不动码（盒子挂在访客网络、
 * 手机开流量的时候）可以手输。
 */
@Composable
fun TvPairPanel(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val rootFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var address by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val started = SourcePairServer.start(vm.pairHandler())
        val ip = if (started) localIpAddress() else null
        val token = SourcePairServer.token
        if (ip != null && token.isNotBlank()) {
            address = "http://$ip:${SourcePairServer.port}/?k=$token"
        } else {
            failed = true
        }
        onDispose { SourcePairServer.stop() }
    }

    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
        kotlinx.coroutines.delay(160)
        runCatching { closeFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Back) {
                    onDismiss()
                    true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.width(680.dp),
            shape = Radius.Lg,
            color = AppColors.Surface,
            shadowElevation = 24.dp
        ) {
            Row(
                Modifier.padding(horizontal = 30.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                val url = address
                val qrResult = remember(url) { url?.let { qrBitmap(it) } }
                val qr = qrResult?.first
                val qrError = qrResult?.second
                Box(
                    Modifier
                        .size(232.dp)
                        .background(Color.White, Radius.Md)
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qr != null) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "手机扫码维护地址二维码",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                            filterQuality = FilterQuality.None
                        )
                    } else {
                        Text(
                            text = when {
                                failed -> "起不了服务\n端口被占用"
                                qrError != null -> "生成失败\n$qrError"
                                else -> "正在生成…"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.SurfaceHi,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "手机扫码维护来源",
                        style = MaterialTheme.typography.headlineSmall,
                        color = TvText.Title,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "手机和电视要连同一个网络。扫码后在手机上填表单，点保存就直接写进这台电视。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvText.Body
                    )
                    url?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = AppColors.Brand,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.Bg, Radius.Sm)
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                    Text(
                        text = "退出这一页就停止服务，期间手机能改这台电视的来源配置。",
                        style = MaterialTheme.typography.labelSmall,
                        color = TvText.Muted
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvPillButton(
                            text = "关闭",
                            primary = true,
                            size = TvButtonSize.Sm,
                            modifier = Modifier.focusRequester(closeFocus),
                            onClick = onDismiss
                        )
                    }
                }
            }
        }
    }
}

/**
 * 二维码位图。zxing 只出 BitMatrix，得自己铺成黑白点。
 *
 * 512 是给电视投影留的余量：放大到 232dp 后一个模块仍有约 3 物理像素，
 * 手机摄像头对不上焦的最常见原因就是模块被插值糊成灰。
 */
private fun qrBitmap(text: String, sizePx: Int = 512): Pair<Bitmap?, String?> {
    val t0 = android.os.SystemClock.elapsedRealtime()
    var matrixW = -1
    return try {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
            text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(
                com.google.zxing.EncodeHintType.MARGIN to 1,
                com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            )
        )
        matrixW = matrix.width
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        Log.i(TAG, "二维码 成功 矩阵=$matrixW 耗时=${android.os.SystemClock.elapsedRealtime() - t0}ms")
        bmp to null
    } catch (t: Throwable) {
        // 之前这里是 runCatching{}.getOrNull()：异常被吞成 null，界面上就永远停在「正在生成…」，
        // 慢和挂掉长得一模一样。类名一并摆到屏幕上，没连数据线也能看出根因。
        Log.e(TAG, "二维码 失败 矩阵=$matrixW 耗时=${android.os.SystemClock.elapsedRealtime() - t0}ms", t)
        null to "${t.javaClass.simpleName}: ${t.message}"
    }
}
