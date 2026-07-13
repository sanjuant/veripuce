package fr.veripuce.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scan OCR plein écran : détecte le CAN (mode [MODE_CAN]) ou la MRZ (mode [MODE_MRZ])
 * et renvoie le résultat à l'appelant. 100 % on-device (Tesseract + modèle OCR-B embarqué,
 * cf. [TesseractOcrEngine]) — aucune image ne quitte l'appareil. La saisie manuelle reste le
 * repli : annuler = retour simple.
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
        /** Mode diagnostic actif (propagé depuis MainActivity) : affiche l'OCR brut à l'écran. */
        const val EXTRA_DIAG = "diag"
    }

    /** Moteur OCR (Tesseract + modèle OCR-B). Construit paresseusement SUR le thread d'analyse
     *  (init JNI coûteux) -> jamais touché depuis le thread UI hormis [onDestroy]. */
    @Volatile private var ocr: OcrEngine? = null
    private var ocrInitFailed = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val finished = AtomicBoolean(false)
    private val hintHandler = Handler(Looper.getMainLooper())

    private var camera: Camera? = null
    private var torchOn = false

    /** Mode diagnostic : bandeau live du texte OCR brut (aide au débogage d'une lecture ratée). */
    private var diagMode = false
    private var diagOverlay: TextView? = null

    /** CAN : pas de checksum -> même valeur exigée sur 2 images consécutives. */
    private var lastCan: String? = null

    /** MRZ : vote par position (converge malgré une erreur OCR intermittente). */
    private val vote = MrzVote()

    /** Compteurs de qualité de capture (torche auto, indice de reflet). */
    private var lowLightFrames = 0
    private var glareFrames = 0
    private var autoTorchDone = false
    private var glareHintShown = false

    /** Résolution/rotation effectives de la dernière trame analysée (pour le diagnostic). */
    @Volatile private var lastAnalysisRes: String? = null
    @Volatile private var lastAnalysisRot: Int = 0

    /** Géométrie du viseur (snapshot pour recadrer l'image d'analyse hors du thread UI). */
    @Volatile private var roiRatio = 4.0f
    @Volatile private var roiWidthFraction = 0.94f
    @Volatile private var roiVerticalBias = 0.42f

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

        // Mode diagnostic (propagé par MainActivity) : afficher en direct le texte OCR brut.
        diagMode = intent.getBooleanExtra(EXTRA_DIAG, false)
        if (diagMode) {
            diagOverlay = findViewById<TextView>(R.id.diagOverlay).apply { visibility = View.VISIBLE }
        }

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
            // Même géométrie pour recadrer l'image d'analyse sur cette fenêtre (§ analyze).
            roiRatio = windowRatio
            roiWidthFraction = windowWidthFraction
            roiVerticalBias = windowVerticalBias
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
        lastAnalysisRot = proxy.imageInfo.rotationDegrees
        // On RECADRE sur la fenêtre du viseur avant l'OCR : sur une trame 12 Mpx, la MRZ
        // n'est qu'une lamelle -> l'OCR lit mal. Le crop densifie les caractères OCR-B.
        // La qualité (reflet/lumière) est mesurée SUR CE CROP (la MRZ, mate et éclairée),
        // pas sur toute l'image : le corps brillant de la carte et le fond sombre ne
        // doivent pas déclencher de fausse alerte reflet ni de fausse basse-lumière.
        val crop = runCatching { mrzCropAndAssess(proxy) }.getOrNull()
        proxy.close()                              // trame copiée dans le bitmap -> libérable
        if (crop == null) return

        // Init paresseuse du moteur OCR SUR ce thread d'analyse (mono-thread) : l'init JNI est
        // coûteux, on le fait une seule fois, sans course. Un échec d'init (rare : modèle
        // embarqué) coupe l'OCR -> l'utilisateur bascule en saisie manuelle après le délai.
        val engine = ocr ?: run {
            if (ocrInitFailed) { crop.recycle(); return }
            val created = TesseractOcrEngine.create(this)
            if (created == null) { ocrInitFailed = true; crop.recycle(); return }
            ocr = created
            created
        }

        val text = engine.recognize(crop)
        crop.recycle()
        if (finished.get()) return
        // Retour sur le thread principal : bandeau diagnostic (données réelles -> diag only) puis
        // traitement du texte (setResult/finish, état du vote).
        if (diagMode || text != null) runOnUiThread {
            if (diagMode) showDiagOverlay(text)
            if (!finished.get() && text != null) onText(text)
        }
    }

    /**
     * Mode diagnostic : affiche en direct le texte OCR BRUT lu sur la bande MRZ + un verdict
     * ([MrzOcr.diagnose]) — pour que l'utilisateur comprenne pourquoi ça ne se lit pas (police
     * mal reconnue, ligne manquante, chiffre de contrôle KO). Montre des données réelles : réservé
     * au mode diagnostic, jamais dans le rapport partageable.
     */
    private fun showDiagOverlay(raw: String?) {
        val view = diagOverlay ?: return
        val status = when {
            raw.isNullOrBlank() -> "✗ rien lu — cadrage / netteté / lumière ?"
            mode == MODE_CAN -> if (MrzOcr.findCan(raw) != null) "✓ CAN reconnu" else "✗ CAN non trouvé"
            else -> MrzOcr.diagnose(raw)
        }
        view.text = buildString {
            append("OCR diag · ").append(status)
            if (!raw.isNullOrBlank()) append('\n').append(raw.trim())
        }
    }

    /**
     * Convertit la trame en bitmap redressé, recadre sur la fenêtre du viseur, puis applique
     * le prétraitement (niveaux de gris + contraste). Le moteur OCR reçoit ainsi une image
     * petite et dense (la MRZ en gros), au lieu d'une trame 12 Mpx où les caractères OCR-B
     * sont minuscules. Les bitmaps intermédiaires sont recyclés pour limiter la pression GC ;
     * l'appelant devient propriétaire du bitmap renvoyé (à recycler après l'OCR).
     */
    private fun mrzCropAndAssess(proxy: ImageProxy): Bitmap {
        val rot = proxy.imageInfo.rotationDegrees
        val sensor = proxy.toBitmap()
        val upright = if (rot == 0) {
            sensor
        } else {
            Bitmap.createBitmap(sensor, 0, 0, sensor.width, sensor.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                .also { if (it !== sensor) sensor.recycle() }
        }
        val roi = ScanRoi.mrzRoi(upright.width, upright.height, roiRatio, roiWidthFraction, roiVerticalBias)
        val crop = Bitmap.createBitmap(upright, roi.left, roi.top, roi.width, roi.height)
            .also { if (it !== upright) upright.recycle() }
        // Qualité (reflet/lumière) mesurée sur le crop BRUT — reflète l'image réelle, avant
        // que l'étirement de contraste ne modifie luminance et saturation.
        updateCaptureHints(cropQuality(crop))
        val w = crop.width
        val h = crop.height
        // Niveaux de gris + étirement de contraste : rend lisibles les MRZ gravées laser en
        // faible contraste (cf. MrzImage). Recycle le crop brut.
        val enhanced = enhanceMrz(crop)
        lastAnalysisRes = "${w}x${h} contraste↑ (ROI de ${proxy.width}x${proxy.height})"
        return enhanced
    }

    /**
     * Niveaux de gris + étirement de contraste (percentiles robustes, cf. [MrzImage]) du crop
     * MRZ, avant de le passer au moteur OCR (Tesseract binarise ensuite en interne, et un bon
     * contraste aide son seuillage). Un balayage pour l'histogramme, un second pour appliquer
     * la LUT. Le bitmap source est recyclé ; un nouveau bitmap gris est renvoyé.
     */
    private fun enhanceMrz(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val luma = IntArray(px.size)
        val hist = IntArray(256)
        for (i in px.indices) {
            val p = px[i]
            val y = (((p ushr 16) and 0xFF) * 77 + ((p ushr 8) and 0xFF) * 151 + (p and 0xFF) * 28) ushr 8
            luma[i] = y
            hist[y]++
        }

        val lut = MrzImage.contrastLut(hist)
        for (i in px.indices) {
            val y = lut[luma[i]]
            px[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        src.recycle()
        return out
    }

    /** Luminance moyenne + fraction de pixels saturés du CROP MRZ (échantillonné). */
    private fun cropQuality(bmp: Bitmap): CaptureQuality {
        val step = (minOf(bmp.width, bmp.height) / 40).coerceAtLeast(1)
        var sum = 0L
        var bright = 0
        var count = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val p = bmp.getPixel(x, y)
                val luma = (((p ushr 16) and 0xFF) * 77 + ((p ushr 8) and 0xFF) * 151 + (p and 0xFF) * 28) ushr 8
                sum += luma
                if (luma > 250) bright++
                count++
                x += step
            }
            y += step
        }
        return if (count == 0) CaptureQuality(128, 0f) else CaptureQuality((sum / count).toInt(), bright.toFloat() / count)
    }

    /** Appelé sur le thread principal (remarshalé depuis le thread d'analyse) — pas de course. */
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

    /** Luminance moyenne et fraction de pixels saturés (mesurées sur le crop MRZ). */
    private data class CaptureQuality(val avgLuma: Int, val glareFraction: Float)

    private fun updateCaptureHints(q: CaptureQuality) {
        lowLightFrames = if (q.avgLuma < 60) lowLightFrames + 1 else 0
        // Seuil de reflet mesuré SUR LA MRZ (mate) : on peut être bien plus permissif
        // qu'avant, où l'on mesurait le corps brillant de la carte et déclenchait à tort.
        glareFrames = if (q.glareFraction > 0.04f) glareFrames + 1 else 0
        runOnUiThread {
            if (finished.get()) return@runOnUiThread
            // Basse lumière soutenue -> torche auto (une seule fois, sans écraser un choix manuel).
            if (!autoTorchDone && !torchOn && lowLightFrames >= 12 &&
                camera?.cameraInfo?.hasFlashUnit() == true
            ) {
                autoTorchDone = true
                setTorch(true)
            }
            val hint = findViewById<TextView>(R.id.scanHint)
            if (glareFrames >= 10) {
                // Reflet spéculaire répété SUR LA MRZ -> conseiller d'incliner la carte.
                hint.setText(R.string.scan_glare)
                glareHintShown = true
            } else if (glareHintShown && glareFrames == 0) {
                // Reflet dissipé : restaurer l'indice normal, ne pas laisser l'alerte bloquer.
                hint.setText(if (mode == MODE_CAN) R.string.scan_hint_can else R.string.scan_hint_mrz)
                glareHintShown = false
            }
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
        // Attendre la fin de l'analyse en cours AVANT de libérer Tesseract (non thread-safe :
        // recycle() pendant une reconnaissance planterait le natif).
        analysisExecutor.shutdown()
        runCatching { analysisExecutor.awaitTermination(600, TimeUnit.MILLISECONDS) }
        ocr?.close()
        ocr = null
    }
}
