package com.hayoonjae.asciicam

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** 밝은 그라디언트 패널 위에 올라가는 글자색. */
private val Ink = Color(0xFF16161D)
private val InkDim = Color(0x9916161D)

@Composable
fun CameraScreen(vm: AsciiViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(executor) { onDispose { executor.shutdown() } }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) requestPermission.launch(Manifest.permission.CAMERA)
    }

    val settings = vm.settings
    // 카메라를 바꿀 때만 다시 바인딩한다. 나머지 설정은 분석 스레드가 매 프레임 읽어 간다.
    LaunchedEffect(granted, settings.frontCamera) {
        if (!granted) return@LaunchedEffect
        CameraSession.bind(
            context = context,
            lifecycleOwner = lifecycleOwner,
            executor = executor,
            front = settings.frontCamera,
            onFrame = vm::analyze,
            onError = { t ->
                scope.launch { snackbar.showSnackbar("카메라를 열지 못했습니다: " + (t.message ?: "알 수 없는 오류")) }
            },
        )
    }

    var panelOpen by rememberSaveable { mutableStateOf(true) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { _ ->
        Box(Modifier.fillMaxSize()) {
            AsciiView(
                frame = vm.frame,
                settings = settings,
                onGrid = vm::setGrid,
                modifier = Modifier.fillMaxSize(),
            )

            if (!granted) {
                PermissionNotice(
                    onRequest = { requestPermission.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            StatusChip(
                text = "${settings.cols}칸 · ${vm.fps}fps",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp),
            )

            ControlPanel(
                settings = settings,
                open = panelOpen,
                onToggleOpen = { panelOpen = !panelOpen },
                onChange = vm::update,
                onCapture = {
                    val frame = vm.frame
                    if (frame == null) {
                        scope.launch { snackbar.showSnackbar("아직 들어온 프레임이 없습니다") }
                    } else {
                        scope.launch {
                            val message = withContext(Dispatchers.IO) {
                                runCatching { Capture.savePng(context, frame, settings) }
                            }.fold(
                                onSuccess = { "갤러리 AsciiCam 에 저장했습니다 ($it)" },
                                onFailure = { "저장 실패: " + (it.message ?: "알 수 없는 오류") },
                            )
                            snackbar.showSnackbar(message)
                        }
                    }
                },
                onCopy = {
                    val frame = vm.frame
                    if (frame == null) {
                        scope.launch { snackbar.showSnackbar("아직 들어온 프레임이 없습니다") }
                    } else {
                        clipboard.setText(AnnotatedString(frame.toText()))
                        scope.launch { snackbar.showSnackbar("ASCII 텍스트를 복사했습니다") }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun PermissionNotice(onRequest: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(32.dp)
            .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("카메라 권한이 필요합니다", color = Ink, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        Text(
            "화면에 보이는 건 ASCII 글자뿐이고, 카메라 영상은 기기 밖으로 나가지 않습니다.",
            color = InkDim,
            fontSize = 13.sp,
        )
        Spacer(Modifier.size(16.dp))
        Button(onRequest) { Text("권한 허용하기") }
    }
}

@Composable
private fun StatusChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = Color.White,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun ControlPanel(
    settings: Settings,
    open: Boolean,
    onToggleOpen: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
    onCapture: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gradient = Brush.linearGradient(
        listOf(Color(settings.gradient.start), Color(settings.gradient.end))
    )
    Column(
        modifier
            .fillMaxWidth()
            .padding(10.dp)
            .background(gradient, RoundedCornerShape(24.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelButton(
                icon = Icons.Filled.Cameraswitch,
                label = "전환",
                onClick = { onChange { it.copy(frontCamera = !it.frontCamera) } },
                modifier = Modifier.weight(1f),
            )
            PanelButton(
                icon = Icons.Filled.Download,
                label = "저장",
                onClick = onCapture,
                modifier = Modifier.weight(1f),
            )
            PanelButton(
                icon = Icons.Filled.ContentCopy,
                label = "복사",
                onClick = onCopy,
                modifier = Modifier.weight(1f),
            )
            PanelButton(
                icon = if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                label = if (open) "접기" else "설정",
                onClick = onToggleOpen,
                modifier = Modifier.weight(1f),
            )
        }

        if (open) {
            SectionLabel("색")
            ChipRow(
                items = ColorMode.entries,
                selected = settings.colorMode,
                label = { it.label },
                onSelect = { mode -> onChange { it.copy(colorMode = mode) } },
            )

            SectionLabel("그라디언트")
            ChipRow(
                items = GradientPreset.entries,
                selected = settings.gradient,
                label = { it.label },
                onSelect = { preset -> onChange { it.copy(gradient = preset) } },
            )

            SectionLabel("글자")
            ChipRow(
                items = Ramp.entries,
                selected = settings.ramp,
                label = { it.label },
                onSelect = { ramp -> onChange { it.copy(ramp = ramp) } },
            )

            LabeledSlider(
                label = "밀도",
                value = settings.cols.toFloat(),
                range = 30f..200f,
                display = "${settings.cols}칸",
                onChange = { v -> onChange { it.copy(cols = v.roundToInt()) } },
            )
            LabeledSlider(
                label = "대비",
                value = settings.contrast,
                range = 0.4f..2.6f,
                display = String.format("%.1f배", settings.contrast),
                onChange = { v -> onChange { it.copy(contrast = v) } },
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LabeledSwitch(
                    label = "자동 보정",
                    checked = settings.autoLevels,
                    onChange = { v -> onChange { it.copy(autoLevels = v) } },
                    modifier = Modifier.weight(1f),
                )
                LabeledSwitch(
                    label = "밝은 배경",
                    checked = settings.lightBackground,
                    onChange = { v -> onChange { it.copy(lightBackground = v) } },
                    modifier = Modifier.weight(1f),
                )
                LabeledSwitch(
                    label = "반전",
                    checked = settings.invert,
                    onChange = { v -> onChange { it.copy(invert = v) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PanelButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White.copy(alpha = 0.85f),
            contentColor = Ink,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun <T> ChipRow(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item), fontSize = 12.sp) },
                shape = RoundedCornerShape(999.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.White.copy(alpha = 0.35f),
                    labelColor = Ink,
                    selectedContainerColor = Ink,
                    selectedLabelColor = Color.White,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel(label)
            Text(display, color = InkDim, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Ink,
                activeTrackColor = Ink,
                inactiveTrackColor = Color.White.copy(alpha = 0.55f),
            ),
        )
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Ink, fontSize = 11.sp, maxLines = 1)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Ink,
                uncheckedThumbColor = Ink,
                uncheckedTrackColor = Color.White.copy(alpha = 0.55f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}
