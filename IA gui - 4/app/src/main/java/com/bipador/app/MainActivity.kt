package com.bipador.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcodescanning.BarcodeScannerOptions
import com.google.mlkit.vision.barcodescanning.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val codes = mutableListOf<Pair<String, Long>>()
    private val lastSeen = mutableMapOf<String, Long>()
    private lateinit var txtLastCode: android.widget.TextView
    private lateinit var txtHistory: android.widget.TextView
    private lateinit var txtCount: android.widget.TextView
    private lateinit var historyScroll: ScrollView
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        txtLastCode = findViewById(R.id.txtLastCode)
        txtHistory = findViewById(R.id.txtHistory)
        txtCount = findViewById(R.id.txtCount)
        historyScroll = findViewById(R.id.txtHistory.parent as ScrollView)
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportCSV() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            codes.clear()
            updateUI()
            Toast.makeText(this, "Histórico limpo", Toast.LENGTH_SHORT).show()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<String>, grants: IntArray) {
        super.onRequestPermissionsResult(rc, perms, grants)
        if (rc == 100 && grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Permissão de câmera negada", Toast.LENGTH_LONG).show()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
            }
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                    Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_ITF, Barcode.FORMAT_CODABAR, Barcode.FORMAT_QR_CODE
                )
                .build()
            val scanner = com.google.mlkit.vision.barcodescanning.BarcodeScanning.getClient(options)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                @androidx.camera.core.ExperimentalGetImage
                val media = proxy.image
                if (media != null) {
                    val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            for (b in barcodes) {
                                b.rawValue?.let { maybeAdd(it) }
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                } else proxy.close()
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun maybeAdd(code: String) {
        val now = System.currentTimeMillis()
        synchronized(lastSeen) {
            if (lastSeen[code] != null && now - lastSeen[code]!! < 2500) return
            lastSeen[code] = now
        }
        runOnUiThread {
            val dup = codes.any { it.first == code }
            codes.add(code to now)
            beepAndVibrate()
            txtLastCode.text = if (dup) "⚠ REPETIDO: $code" else "✔ $code"
            txtLastCode.setTextColor(
                if (dup) 0xFFFFB84D.toInt() else 0xFF4CAF50.toInt()
            )
            updateUI()
        }
    }

    private fun beepAndVibrate() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 200)
        } catch (_: Exception) {}
        @Suppress("DEPRECATION")
        val vib = getSystemService(Vibrator::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(120)
        }
    }

    private fun updateUI() {
        txtCount.text = "Códigos bipados: ${codes.size}"
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        txtHistory.text = codes.reversed().joinToString("\n") { (code, t) ->
            "${fmt.format(Date(t))}  $code"
        }
    }

    private fun exportCSV() {
        if (codes.isEmpty()) {
            Toast.makeText(this, "Nenhum código bipado ainda", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val downloads = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
            else File(Environment.getExternalStorageDirectory(), "Download")
            downloads.mkdirs()
            val file = File(downloads, "pacotes_${SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault()).format(Date())}.csv")
            FileWriter(file).use { w ->
                w.append("codigo;data;hora;repetido\n")
                val df = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                for ((code, t) in codes) {
                    val dup = if (codes.count { it.first == code } > 1) "sim" else "nao"
                    w.append("$code;${df.format(Date(t))};$dup\n")
                }
            }
            Toast.makeText(this, "CSV salvo em: ${file.absolutePath}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao exportar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}