package com.nmerza.cameraapp

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class FilterInfo(val displayName: String, val internalName: String)

private val filterOptions = listOf(
    FilterInfo("None", "None"),
    FilterInfo("Blue Arch", "Blue Architecture"),
    FilterInfo("Hard Boost", "HardBoost"),
    FilterInfo("Morning", "LongBeachMorning"),
    FilterInfo("Lush Green", "LushGreen"),
    FilterInfo("Magic Hour", "MagicHour"),
    FilterInfo("Natural", "NaturalBoost"),
    FilterInfo("Orange/Blue", "OrangeAndBlue"),
    FilterInfo("B&W Soft", "SoftBlackAndWhite"),
    FilterInfo("Waves", "Waves"),
    FilterInfo("Blue Hour", "BlueHour"),
    FilterInfo("Cold Chrome", "ColdChrome"),
    FilterInfo("Autumn", "CrispAutumn"),
    FilterInfo("Somber", "DarkAndSomber")
)

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    lateinit var imageProcessor: ImageProcessor
    private var cameraProvider: ProcessCameraProvider? = null

    private var lastPhotoUri by mutableStateOf<Uri?>(null)

    private val requestPermissionsLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.entries.all { it.value }) {
                Log.d("CameraApp", "All permissions granted")
                setupCameraContent()
            } else {
                Log.e("CameraApp", "Permissions not granted")
                setContent { CameraAppThemeTheme { PermissionDeniedScreen() } }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        imageProcessor = ImageProcessor(NativeFilter(), cameraExecutor)

        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()

        if (permissionsToRequest.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            setupCameraContent()
        } else {
            requestPermissionsLauncher.launch(permissionsToRequest)
        }
    }

    private fun setupCameraContent() {
        lastPhotoUri = findLastPhotoUri()

        setContent {
            CameraAppThemeTheme {
                CameraAppCameraScreen(
                    imageProcessor = imageProcessor,
                    processedBitmapFlow = imageProcessor.processedBitmap,
                    onCapturePhoto = { capturePhoto() },
                    onCameraProviderReady = { provider -> cameraProvider = provider },
                    getLastPhotoUri = { lastPhotoUri }
                )
            }
        }
    }

    private fun capturePhoto() {
        val bitmapToSave = imageProcessor.processedBitmap.value
        if (bitmapToSave == null) {
            Log.e("CameraApp", "Photo capture failed: bitmap was null")
            return
        }

        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraApp")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            try {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    bitmapToSave.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                Log.d("CameraApp", "Photo capture succeeded: $it")
                lastPhotoUri = it
            } catch (e: Exception) {
                Log.e("CameraApp", "Photo capture failed for URI $it", e)
                contentResolver.delete(it, null, null)
            }
        }
    }

    private fun findLastPhotoUri(): Uri? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        return contentResolver.query(
            contentUri, projection, null, null, sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val imageId = cursor.getLong(idColumn)
                ContentUris.withAppendedId(contentUri, imageId)
            } else {
                null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@Composable
fun CameraAppThemeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF13A4EC),
            background = Color(0xFF101C22),
            surface = Color(0xFF101C22)
        ),
        content = content
    )
}

@Composable
fun PermissionDeniedScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Camera permission is required to use this app.",
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Composable
fun CameraAppCameraScreen(
    imageProcessor: ImageProcessor,
    processedBitmapFlow: StateFlow<Bitmap?>,
    onCapturePhoto: () -> Unit,
    onCameraProviderReady: (ProcessCameraProvider) -> Unit,
    getLastPhotoUri: () -> Uri?
) {
    var selectedFilter by remember { mutableStateOf(filterOptions.first()) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var showLastPhoto by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalContext.current as LifecycleOwner
    val context = LocalContext.current

    LaunchedEffect(lensFacing) {
        imageProcessor.lensFacing = lensFacing
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        ProcessedCameraPreview(
            imageProcessor = imageProcessor,
            modifier = Modifier.fillMaxSize(),
            lensFacing = lensFacing,
            onCameraProviderReady = onCameraProviderReady,
            lifecycleOwner = lifecycleOwner,
            processedBitmapFlow = processedBitmapFlow
        )

        TopAppBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
        )

        BottomSheetUI(
            modifier = Modifier.align(Alignment.BottomCenter),
            selectedFilter = selectedFilter,
            onFilterSelected = { filter ->
                selectedFilter = filter
                imageProcessor.setActiveFilter(filter.internalName)
                Log.d("CameraApp", "Selected filter: ${filter.displayName}")
            },
            onSwitchCamera = {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            },
            onCapturePhoto = onCapturePhoto,
            getLastPhotoUri = getLastPhotoUri,
            onThumbnailClick = { showLastPhoto = true }
        )
    }

    if (showLastPhoto) {
        val lastUri = getLastPhotoUri()
        if (lastUri != null) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(lastUri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("CameraApp", "Could not launch photo viewer", e)
            }
        } else {
            Log.d("CameraApp", "Thumbnail clicked. No photo saved yet.")
        }
        showLastPhoto = false
    }
}

@Composable
fun ProcessedCameraPreview(
    imageProcessor: ImageProcessor,
    modifier: Modifier = Modifier,
    lensFacing: Int,
    onCameraProviderReady: (ProcessCameraProvider) -> Unit,
    lifecycleOwner: LifecycleOwner,
    processedBitmapFlow: StateFlow<Bitmap?>
) {
    val context = LocalContext.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val bitmap by processedBitmapFlow.collectAsState()

    LaunchedEffect(lensFacing) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            onCameraProviderReady(cameraProvider)

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, imageProcessor)
                }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("CameraApp", "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Real-time camera preview with filter",
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier = modifier.background(Color.Black))
    }
}

@Composable
fun TopAppBar(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Camera App",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ThumbnailPreview(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    lastPhotoUri: Uri?
) {
    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (lastPhotoUri != null) {
            AsyncImage(
                model = lastPhotoUri,
                contentDescription = "Last captured photo thumbnail",
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Last photo (none available)",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun BottomSheetUI(
    modifier: Modifier = Modifier,
    selectedFilter: FilterInfo,
    onFilterSelected: (FilterInfo) -> Unit,
    onSwitchCamera: () -> Unit,
    onCapturePhoto: () -> Unit,
    getLastPhotoUri: () -> Uri?,
    onThumbnailClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Color(0xFF101C22),
//                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            )
            .padding(bottom = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF4A5568))
            )
        }

        Text(
            text = "Choose Filter",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF9CA3AF),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            textAlign = TextAlign.Center
        )

        FilterSelectorRow(
            selectedFilter = selectedFilter,
            onFilterSelected = onFilterSelected,
            modifier = Modifier.padding(vertical = 12.dp)
        )

        CameraControlsRow(
            onSwitchCamera = onSwitchCamera,
            onCapturePhoto = onCapturePhoto,
            getLastPhotoUri = getLastPhotoUri,
            onThumbnailClick = onThumbnailClick,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
fun FilterSelectorRow(
    selectedFilter: FilterInfo,
    onFilterSelected: (FilterInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(filterOptions) { filter ->
            FilterButton(
                text = filter.displayName,
                isSelected = filter.internalName == selectedFilter.internalName,
                onClick = { onFilterSelected(filter) }
            )
        }
    }
}

@Composable
fun FilterButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) Color(0xFF13A4EC) else Color(0xFF1F2937).copy(alpha = 0.8f),
            contentColor = if (isSelected) Color.White else Color(0xFF9CA3AF)
        ),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun CameraControlsRow(
    onSwitchCamera: () -> Unit,
    onCapturePhoto: () -> Unit,
    modifier: Modifier = Modifier,
    getLastPhotoUri: () -> Uri?,
    onThumbnailClick: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ThumbnailPreview(
            onClick = onThumbnailClick,
            lastPhotoUri = getLastPhotoUri()
        )

        CaptureButton(onClick = onCapturePhoto)

        RoundIconButton(
            icon = Icons.Default.FlipCameraAndroid,
            onClick = onSwitchCamera,
            size = 48.dp
        )
    }
}

@Composable
fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    size: Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.1f)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFFE5E7EB),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun CaptureButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(80.dp),
        shape = CircleShape,
        color = Color.White
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Color(0xFF13A4EC))
            )
        }
    }
}
