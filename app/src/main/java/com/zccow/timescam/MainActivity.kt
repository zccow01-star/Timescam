package com.zccow.timescam

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.location.Geocoder
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var beautyLevel: Float = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        val btnCapture = findViewById<Button>(R.id.btnCapture)
        val seekBarBeauty = findViewById<SeekBar>(R.id.seekBarBeauty)

        seekBarBeauty.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                beautyLevel = progress / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        btnCapture.setOnClickListener { takePhoto() }
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Gagal membuka kamera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = imageProxyToBitmap(image)
                    image.close()

                    // Apply Beauty & Timestamp
                    val beautified = applyBeautyFilter(bitmap, beautyLevel)
                    fetchLocationAndOverlay(beautified)
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(applicationContext, "Gagal mengambil gambar", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun applyBeautyFilter(src: Bitmap, level: Float): Bitmap {
        if (level <= 0f) return src
        val result = src.copy(Bitmap.Config.ARGB_8888, true)
        // Basic Smoothing filter via Canvas blur simulation
        val canvas = Canvas(result)
        val paint = Paint().apply {
            alpha = (level * 80).toInt()
            maskFilter = BlurMaskFilter(15f * level, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawBitmap(result, 0f, 0f, paint)
        return result
    }

    private fun fetchLocationAndOverlay(bitmap: Bitmap) {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val timeStamp = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault()).format(Date())
                var locationText = "GPS: Unavailable"

                if (location != null) {
                    val geocoder = Geocoder(this, Locale.getDefault())
                    val addresses = try { geocoder.getFromLocation(location.latitude, location.longitude, 1) } catch (e: Exception) { null }
                    val address = addresses?.firstOrNull()?.getAddressLine(0) ?: ""
                    locationText = "${location.latitude}, ${location.longitude}\n$address"
                }

                val finalImage = overlayTimestamp(bitmap, "$timeStamp\n$locationText")
                saveImageToStorage(finalImage)
            }
        } else {
            val timeStamp = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault()).format(Date())
            val finalImage = overlayTimestamp(bitmap, timeStamp)
            saveImageToStorage(finalImage)
        }
    }

    private fun overlayTimestamp(bitmap: Bitmap, text: String): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = bitmap.height * 0.025f
            isAntiAlias = true
            setShadowLayer(5f, 2f, 2f, Color.BLACK)
        }

        val lines = text.split("\n")
        var y = bitmap.height - (lines.size * paint.textSize) - 40f
        for (line in lines) {
            canvas.drawText(line, 40f, y, paint)
            y += paint.textSize + 10f
        }
        return mutableBitmap
    }

    private fun saveImageToStorage(bitmap: Bitmap) {
        val file = File(getExternalFilesDir(null), "Timescam_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
        Toast.makeText(this, "Foto tersimpan: ${file.name}", Toast.LENGTH_LONG).show()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}
