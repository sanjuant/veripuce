package fr.veripuce.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        const val EXTRA_DOCTYPE = "doctype"
        const val EXTRA_STATE = "state"
        const val EXTRA_OCR_RES = "ocr_res"
        const val EXTRA_OCR_ROT = "ocr_rot"
        const val EXTRA_PERMISSION_DENIED = "permission_denied"
        const val EXTRA_PERMISSION_PERMANENT = "permission_permanent"
        const val EXTRA_CAMERA_UNAVAILABLE = "camera_unavailable"
        const val EXTRA_MANUAL_REQUESTED = "manual_requested"
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val finished = AtomicBoolean(false)
    private val hintHandler = Handler(Looper.getMainLooper())

    private var camera: Camera? = null
    private var torchOn = false

    /** CAN : pas de checksum -> même valeur exigée sur 2 images consécutives. */
    private var lastCan: String? = null

    /** MRZ : vote par position (converge malgré une erreur OCR intermittente). */
    private val vote = MrzVote()

    /** Compteurs de qualité de capture (torche auto, indice de reflet). */
    private var lowLightFrames = 0
    private var glareFrames = 0
    private var autoTorchDone = false

    /** Résolution/rotation effectives de la dernière trame analysée (pour le diagnostic). */
    @Volatile private var lastAnalysisRes: String? = null
    @Volatile private var lastAnalysisRot: Int = 0

    /** Repli : renvoyer à la saisie manuelle (lumière insuffisante, MRZ illisible…). */
    private fun requestManualEntry() {
        if (finished.compareAndSet(false, true)) {
            setResult(RESULT_CANCELED, Intent().putExtra(EXTRA_MANUAL_REQUESTED, true))
            finish()
        }
    }

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

        // Viseur adapté au document : recto de carte (ID-1) pour le CAN,
        // bande MRZ large pour le passeport / dos de CNIe.
        findViewById<ScanOverlayView>(R.id.overlay).apply {
            if (mode == MODE_CAN) {
                windowRatio = 1.586f          // format carte ID-1 (85,6 x 54 mm)
                windowWidthFraction = 0.82f
                windowVerticalBias = 0.38f
            } else {
                windowRatio = 4.0f            // bande MRZ (2 lignes TD3 / 3 lignes TD1)
                windowWidthFraction = 0.94f
                windowVerticalBias = 0.42f
                // Pièce d'identité fantôme au-dessus de la fenêtre : montre que la
                // bande MRZ à cadrer est le bas du document.
                showCardPlaceholder = true
            }
        }

        // Repli manuel (toujours dispo) et lampe torche (utile en basse lumière).
        findViewById<MaterialButton>(R.id.scanManual).setOnClickListener { requestManualEntry() }
        findViewById<MaterialButton>(R.id.scanTorch).setOnClickListener { setTorch(!torchOn) }

        // Après un délai sans lecture réussie, suggérer la lampe / la saisie manuelle.
        hintHandler.postDelayed({
            if (!finished.get()) findViewById<TextView>(R.id.scanHint).setText(R.string.scan_lowlight)
        }, 12_000)

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
                // sur la PETITE dimension de l'image d'analyse. À 1080p cela reste juste
                // pour l'OCR-B ; viser 1440p (le débit est régulé par KEEP_ONLY_LATEST).
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(2560, 1440),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, analysis)
                // Bouton torche visible seulement si l'appareil a un flash.
                findViewById<MaterialButton>(R.id.scanTorch).visibility =
                    if (camera?.cameraInfo?.hasFlashUnit() == true) View.VISIBLE else View.GONE
                // Refaire la mise au point périodiquement sur le centre de la fenêtre.
                hintHandler.postDelayed(focusRunnable, 1_500)
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
        lastAnalysisRes = "${media.width}x${media.height}"
        lastAnalysisRot = proxy.imageInfo.rotationDegrees
        // Qualité de capture (plan Y, sans conversion bitmap) : torche auto en basse
        // lumière, indice de reflet sur le polycarbonate de la CNIe.
        runCatching { captureQuality(media) }.getOrNull()?.let(::updateCaptureHints)

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

    /** Appelé sur le thread principal (callbacks ML Kit) — pas de course sur l'état. */
    private fun onText(text: String) {
        if (finished.get()) return
        when (mode) {
            MODE_CAN -> {
                // Pas de checksum sur le CAN -> exiger la même valeur sur 2 images.
                val can = MrzOcr.findCan(text) ?: return
                if (lastCan == can) finishWithCan(can) else lastCan = can
            }
            else -> {
                // Chiffres de contrôle ICAO + plausibilité des dates, puis VOTE par position
                // (converge malgré une erreur OCR intermittente sur une paire aveugle).
                val mrz = MrzOcr.findMrz(text) ?: return
                when (val decision = vote.offer(mrz)) {
                    // Conflit de paire aveugle : on renvoie le numéro voté ; le lecteur
                    // essaiera ses variantes en PACE (cf. MrzKeyCandidates).
                    is MrzVote.Decision.Confident -> finishWithMrz(decision.mrz)
                    is MrzVote.Decision.BlindPairConflict -> finishWithMrz(decision.mrz)
                    null -> {}
                }
            }
        }
    }

    private fun finishWithMrz(mrz: MrzOcr.MrzData) {
        if (finished.compareAndSet(false, true)) {
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_MODE, mode)
                    .putExtra(EXTRA_DOC, mrz.documentNumber)
                    .putExtra(EXTRA_DOB, mrz.dateOfBirth)
                    .putExtra(EXTRA_EXP, mrz.dateOfExpiry)
                    .putExtra(EXTRA_DOCTYPE, mrz.docType.name)
                    .putExtra(EXTRA_STATE, mrz.issuingState)
                    .putExtra(EXTRA_OCR_RES, lastAnalysisRes)
                    .putExtra(EXTRA_OCR_ROT, lastAnalysisRot),
            )
            finish()
        }
    }

    private fun finishWithCan(can: String) {
        if (finished.compareAndSet(false, true)) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_MODE, mode).putExtra(EXTRA_CAN, can))
            finish()
        }
    }

    /** Luminance moyenne et fraction de pixels saturés sur la bande centrale (plan Y). */
    private data class CaptureQuality(val avgLuma: Int, val glareFraction: Float)

    private fun captureQuality(image: android.media.Image): CaptureQuality {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val step = 16
        var sum = 0L
        var bright = 0
        var count = 0
        var row = image.height / 4
        while (row < image.height * 3 / 4) {
            val base = row * rowStride
            var col = 0
            while (col < image.width) {
                val idx = base + col * pixelStride
                if (idx >= buf.limit()) break
                val v = buf.get(idx).toInt() and 0xFF
                sum += v
                if (v > 250) bright++
                count++
                col += step
            }
            row += step
        }
        return if (count == 0) CaptureQuality(128, 0f)
        else CaptureQuality((sum / count).toInt(), bright.toFloat() / count)
    }

    private fun updateCaptureHints(q: CaptureQuality) {
        lowLightFrames = if (q.avgLuma < 60) lowLightFrames + 1 else 0
        glareFrames = if (q.glareFraction > 0.015f) glareFrames + 1 else 0
        runOnUiThread {
            if (finished.get()) return@runOnUiThread
            // Basse lumière soutenue -> torche auto (une seule fois, sans écraser un choix manuel).
            if (!autoTorchDone && !torchOn && lowLightFrames >= 12 &&
                camera?.cameraInfo?.hasFlashUnit() == true
            ) {
                autoTorchDone = true
                setTorch(true)
            }
            // Reflet spéculaire répété -> conseiller d'incliner la carte.
            if (glareFrames >= 10) findViewById<TextView>(R.id.scanHint).setText(R.string.scan_glare)
        }
    }

    private fun setTorch(on: Boolean) {
        torchOn = on
        camera?.cameraControl?.enableTorch(on)
        findViewById<MaterialButton>(R.id.scanTorch)
            .setIconResource(if (on) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
    }

    /** Mise au point périodique sur le centre de la fenêtre MRZ (améliore la netteté). */
    private val focusRunnable = object : Runnable {
        override fun run() {
            if (finished.get()) return
            val pv = findViewById<PreviewView>(R.id.preview)
            if (pv.width > 0 && pv.height > 0) {
                runCatching {
                    val point = pv.meteringPointFactory.createPoint(pv.width / 2f, pv.height * 0.42f)
                    camera?.cameraControl?.startFocusAndMetering(
                        FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(2, TimeUnit.SECONDS).build()
                    )
                }
            }
            hintHandler.postDelayed(this, 2_500)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hintHandler.removeCallbacksAndMessages(null)
        analysisExecutor.shutdown()
        recognizer.close()
    }
}
