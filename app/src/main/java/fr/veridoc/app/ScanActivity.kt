package fr.veridoc.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scan OCR plein écran : détecte le CAN (mode [MODE_CAN]) ou la MRZ (mode [MODE_MRZ])
 * et renvoie le résultat à l'appelant. 100 % on-device (ML Kit bundled) — aucune image
 * ne quitte l'appareil. La saisie manuelle reste le repli : annuler = retour simple.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CAN = "can"
        const val MODE_MRZ = "mrz"
        const val EXTRA_CAN = "can_value"
        const val EXTRA_DOC = "doc"
        const val EXTRA_DOB = "dob"
        const val EXTRA_EXP = "exp"
        const val EXTRA_PERMISSION_DENIED = "permission_denied"
        const val EXTRA_PERMISSION_PERMANENT = "permission_permanent"
        const val EXTRA_CAMERA_UNAVAILABLE = "camera_unavailable"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val finished = AtomicBoolean(false)

    /** Stabilité inter-images : même valeur exigée sur 2 images consécutives. */
    private var lastCan: String? = null
    private var lastMrz: MrzOcr.MrzData? = null

    private val mode: String get() = intent.getStringExtra(EXTRA_MODE) ?: MODE_MRZ

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                // rationale=false juste après un refus = « ne plus demander » (bloqué réglages)
                val permanent = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                setResult(
                    RESULT_CANCELED,
                    Intent()
                        .putExtra(EXTRA_PERMISSION_DENIED, true)
                        .putExtra(EXTRA_PERMISSION_PERMANENT, permanent),
                )
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        findViewById<TextView>(R.id.scanHint).setText(
            if (mode == MODE_CAN) R.string.scan_hint_can else R.string.scan_hint_mrz
        )

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            // Le manifeste déclare la caméra OPTIONNELLE (required=false) : sur un
            // appareil sans caméra arrière (ou init caméra en échec), on doit retomber
            // proprement sur la saisie manuelle, jamais crasher.
            try {
                val provider = future.get()
                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> error("Aucune caméra disponible")
                }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.preview).surfaceProvider)
                }
                // Une MRZ TD3 fait 44 caractères : en téléphone portrait, elle s'étale
                // sur la PETITE dimension de l'image d'analyse. À 720p cela donne
                // ~13 px/caractère — trop bas pour l'OCR-B. Viser 1080p.
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1920, 1080),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
            } catch (e: Exception) {
                if (finished.compareAndSet(false, true)) {
                    setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_CAMERA_UNAVAILABLE, true))
                    finish()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || finished.get()) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                // L'ordre des blocs ML Kit n'est pas garanti : reconstruire le texte
                // avec les lignes triées de haut en bas (essentiel pour la MRZ TD1,
                // dont le n° de document précède les dates).
                val sorted = result.textBlocks
                    .flatMap { it.lines }
                    .sortedBy { it.boundingBox?.top ?: 0 }
                    .joinToString("\n") { it.text }
                onText(sorted)
            }
            .addOnCompleteListener { proxy.close() } // libère l'image dans tous les cas
    }

    /** Appelé sur le thread principal (callbacks ML Kit) — pas de course sur lastCan/lastMrz. */
    private fun onText(text: String) {
        if (finished.get()) return
        val data: Intent? = when (mode) {
            MODE_CAN -> MrzOcr.findCan(text)?.let { can ->
                // Pas de checksum sur le CAN -> exiger la même valeur sur 2 images.
                if (lastCan == can) {
                    Intent().putExtra(EXTRA_CAN, can)
                } else {
                    lastCan = can; null
                }
            }
            // MRZ : chiffres de contrôle ICAO + plausibilité de dates + même lecture
            // sur 2 images consécutives (barrière contre les doubles erreurs OCR).
            else -> MrzOcr.findMrz(text)?.let { mrz ->
                if (lastMrz == mrz) {
                    Intent()
                        .putExtra(EXTRA_DOC, mrz.documentNumber)
                        .putExtra(EXTRA_DOB, mrz.dateOfBirth)
                        .putExtra(EXTRA_EXP, mrz.dateOfExpiry)
                } else {
                    lastMrz = mrz; null
                }
            }
        }
        if (data != null && finished.compareAndSet(false, true)) {
            setResult(RESULT_OK, data.putExtra(EXTRA_MODE, mode))
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        recognizer.close()
    }
}
