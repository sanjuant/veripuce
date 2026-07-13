package fr.veripuce.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Flux unique : scanner la MRZ -> le type de document est déduit -> lecture de la puce.
 * Le CAN de la CNIe (recto) reste demandé (il ne figure pas dans la MRZ) ; la MRZ scannée
 * sert alors à vérifier que la puce correspond au document.
 */
class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var readJob: Job? = null

    /** MRZ scannée (ou saisie) : détermine le type de document et sert de référence de cohérence. */
    private var scanned: MrzOcr.MrzData? = null

    /**
     * true après qu'une carte d'identité a refusé la clé MRZ : on exige alors le CAN.
     * Les CNIe (française comprise) acceptent normalement PACE-MRZ — le CAN n'est
     * qu'un repli, plus un préalable.
     */
    private var canFallback = false

    /** Nombre d'échecs de lecture par clé MRZ pour la carte courante (réinitialisé par carte). */
    private var mrzFailures = 0

    /** Itération des variantes du numéro (paires aveugles) tap par tap : candidat courant,
     *  nombre total de variantes, et protocole du dernier tap (pour décider quand avancer). */
    private var mrzOffset = 0
    private var mrzCandidateCount = 1
    private var lastPreferIm = false

    /** N° de tentative de lecture pour la carte courante (indicateur visuel « essai N »). */
    private var readAttempt = 0

    /** Statut posé par le retour de scan : ne pas l'écraser au onResume qui suit. */
    private var statusSetByScan = false

    /** Mode diagnostic (rapport technique caviardé), persistant, désactivé par défaut. */
    private var diagMode = false
    /** Collecteur de la carte courante (réutilisé de l'échec MRZ au repli CAN). */
    private var diagCollector: DiagnosticsCollector? = null
    /** Dernier rapport assemblé, consultable jusqu'à la lecture suivante. */
    private var lastReport: DebugReport? = null
    /** Sel de corrélation, généré par installation, JAMAIS exporté. */
    private val corrSalt: String by lazy { installSalt() }
    /** Résolution/rotation d'analyse effectives du dernier scan (pour le rapport). */
    private var scanOcrRes: String? = null
    private var scanOcrRot: Int? = null

    private lateinit var scanCard: View
    private lateinit var helpBtn: View
    private lateinit var scanMrz: MaterialButton
    private lateinit var manualToggle: MaterialButton
    private lateinit var manualGroup: View
    private lateinit var manualType: MaterialButtonToggleGroup
    private lateinit var manualMrzGroup: View
    private lateinit var manualCanGroup: View

    /** Photo lue sur la puce : originale + version floutée (affichée par défaut). */
    private var photoOriginal: Bitmap? = null
    private var photoBlurred: Bitmap? = null
    private lateinit var docInput: TextInputEditText
    private lateinit var dobInput: TextInputEditText
    private lateinit var expInput: TextInputEditText
    private lateinit var manualCan: TextInputEditText

    private lateinit var detectedCard: MaterialCardView
    private lateinit var detectedType: TextView
    private lateinit var detectedFields: TextView
    private lateinit var chipMrzValid: Chip
    private lateinit var canGroup: View
    private lateinit var canInput: TextInputEditText
    private lateinit var nfcPrompt: View
    private lateinit var nfcPromptText: TextView

    private lateinit var statusCard: MaterialCardView
    private lateinit var checksContainer: View
    private lateinit var status: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var progress: CircularProgressIndicator

    /** Indicateur de lecture NFC : contact établi + progression par étapes. */
    private lateinit var readingCard: MaterialCardView
    private lateinit var readProgress: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var readStep: TextView

    private lateinit var diagCard: MaterialCardView
    private lateinit var diagText: TextView
    private lateinit var diagActions: View

    private lateinit var resultCard: MaterialCardView
    private lateinit var photo: ShapeableImageView
    private lateinit var nameView: TextView
    private lateinit var expiredBadge: View
    private lateinit var fields: TextView
    private lateinit var extra: TextView
    private lateinit var integrityIcon: ImageView
    private lateinit var integrityLabel: TextView
    private lateinit var rowConsistency: View
    private lateinit var consistencyIcon: ImageView
    private lateinit var consistencyLabel: TextView
    private lateinit var rowClone: View
    private lateinit var cloneIcon: ImageView
    private lateinit var cloneLabel: TextView
    private lateinit var originIcon: ImageView
    private lateinit var originLabel: TextView

    /**
     * Ancre de confiance CSCA (assets/csca/, ~770 certificats) pour prouver l'origine étatique.
     * Chargée en arrière-plan dès le lancement ; la lecture NFC l'attend via [Deferred.await].
     */
    private lateinit var cscaCerts: Deferred<Collection<java.security.cert.X509Certificate>>

    /** Retour du scan OCR : MRZ -> détection ; CAN -> pré-remplit le champ CAN. */
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data ?: return@registerForActivityResult
            when {
                res.resultCode == RESULT_OK && data.getStringExtra(ScanActivity.EXTRA_MODE) == ScanActivity.MODE_MRZ -> {
                    scanOcrRes = data.getStringExtra(ScanActivity.EXTRA_OCR_RES)
                    scanOcrRot = data.getIntExtra(ScanActivity.EXTRA_OCR_ROT, -1).takeIf { it >= 0 }
                    onMrzScanned(
                        MrzOcr.MrzData(
                            documentNumber = data.getStringExtra(ScanActivity.EXTRA_DOC).orEmpty(),
                            dateOfBirth = data.getStringExtra(ScanActivity.EXTRA_DOB).orEmpty(),
                            dateOfExpiry = data.getStringExtra(ScanActivity.EXTRA_EXP).orEmpty(),
                            docType = runCatching {
                                MrzOcr.DocType.valueOf(data.getStringExtra(ScanActivity.EXTRA_DOCTYPE).orEmpty())
                            }.getOrDefault(MrzOcr.DocType.PASSPORT),
                            issuingState = data.getStringExtra(ScanActivity.EXTRA_STATE).orEmpty(),
                        )
                    )
                }

                res.resultCode == RESULT_OK && data.getStringExtra(ScanActivity.EXTRA_MODE) == ScanActivity.MODE_CAN -> {
                    canInput.setText(data.getStringExtra(ScanActivity.EXTRA_CAN))
                    setStatus(getString(R.string.scan_filled), R.drawable.ic_state_ok, R.color.on_ok_container)
                    statusSetByScan = true
                }

                data.getBooleanExtra(ScanActivity.EXTRA_MANUAL_REQUESTED, false) -> {
                    // Repli demandé depuis le scanner (basse lumière, MRZ illisible…).
                    manualGroup.visibility = View.VISIBLE
                    docInput.requestFocus()
                    setStatus(getString(R.string.scan_first), R.drawable.ic_state_info, R.color.on_neutral_container)
                    statusSetByScan = true
                }

                data.getBooleanExtra(ScanActivity.EXTRA_CAMERA_UNAVAILABLE, false) -> {
                    warn(getString(R.string.camera_unavailable)); statusSetByScan = true
                    manualGroup.visibility = View.VISIBLE
                }

                data.getBooleanExtra(ScanActivity.EXTRA_PERMISSION_DENIED, false) -> {
                    val blocked = data.getBooleanExtra(ScanActivity.EXTRA_PERMISSION_PERMANENT, false)
                    warn(getString(if (blocked) R.string.camera_blocked else R.string.camera_denied))
                    statusSetByScan = true
                    manualGroup.visibility = View.VISIBLE
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Remplacer le Bouncy Castle partiel d'Android par la version complète.
        Security.removeProvider("BC")
        Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())

        scanCard = findViewById(R.id.scanCard)
        helpBtn = findViewById(R.id.help)
        findViewById<MaterialButton>(R.id.newCheck).setOnClickListener { resetToScan() }
        manualType = findViewById(R.id.manualType)
        manualMrzGroup = findViewById(R.id.manualMrzGroup)
        manualCanGroup = findViewById(R.id.manualCanGroup)
        manualType.addOnButtonCheckedListener { _, _, _ -> updateManualFields() }
        updateManualFields()
        bindHoldToReveal(findViewById(R.id.revealSensitive))
        scanMrz = findViewById(R.id.scanMrz)
        manualToggle = findViewById(R.id.manualToggle)
        manualGroup = findViewById(R.id.manualGroup)
        docInput = findViewById(R.id.docInput)
        dobInput = findViewById(R.id.dobInput)
        expInput = findViewById(R.id.expInput)
        manualCan = findViewById(R.id.manualCan)
        detectedCard = findViewById(R.id.detectedCard)
        detectedType = findViewById(R.id.detectedType)
        detectedFields = findViewById(R.id.detectedFields)
        bindHoldToReveal(findViewById(R.id.revealDetected))
        chipMrzValid = findViewById(R.id.chipMrzValid)
        canGroup = findViewById(R.id.canGroup)
        canInput = findViewById(R.id.canInput)
        nfcPrompt = findViewById(R.id.nfcPrompt)
        nfcPromptText = findViewById(R.id.nfcPromptText)
        statusCard = findViewById(R.id.statusCard)
        checksContainer = findViewById(R.id.checksContainer)
        readingCard = findViewById(R.id.readingCard)
        readProgress = findViewById(R.id.readProgress)
        readStep = findViewById(R.id.readStep)
        status = findViewById(R.id.status)
        statusIcon = findViewById(R.id.statusIcon)
        progress = findViewById(R.id.progress)
        resultCard = findViewById(R.id.resultCard)
        photo = findViewById(R.id.photo)
        nameView = findViewById(R.id.name)
        expiredBadge = findViewById(R.id.expiredBadge)
        fields = findViewById(R.id.fields)
        extra = findViewById(R.id.extra)
        integrityIcon = findViewById(R.id.integrityIcon)
        integrityLabel = findViewById(R.id.integrityLabel)
        rowConsistency = findViewById(R.id.rowConsistency)
        consistencyIcon = findViewById(R.id.consistencyIcon)
        consistencyLabel = findViewById(R.id.consistencyLabel)
        rowClone = findViewById(R.id.rowClone)
        cloneIcon = findViewById(R.id.cloneIcon)
        cloneLabel = findViewById(R.id.cloneLabel)
        originIcon = findViewById(R.id.originIcon)
        originLabel = findViewById(R.id.originLabel)
        cscaCerts = lifecycleScope.async(Dispatchers.IO) {
            CscaStore.load(this@MainActivity).certificates
        }

        // Mode diagnostic : persistant, basculé par appui long sur le titre.
        diagCard = findViewById(R.id.diagCard)
        diagText = findViewById(R.id.diagText)
        diagActions = findViewById(R.id.diagActions)
        diagMode = diagPrefs().getBoolean(PREF_DIAG_MODE, false)
        findViewById<TextView>(R.id.headerTitle).setOnLongClickListener { toggleDiagMode(); true }
        findViewById<TextView>(R.id.diagHeader).setOnClickListener { toggleDiagDetails() }
        findViewById<MaterialButton>(R.id.diagCopy).setOnClickListener { copyReport() }
        findViewById<MaterialButton>(R.id.diagShare).setOnClickListener { shareReport() }

        val hasCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        scanMrz.visibility = if (hasCamera) View.VISIBLE else View.GONE
        scanMrz.setOnClickListener { launchScan(ScanActivity.MODE_MRZ) }
        findViewById<MaterialButton>(R.id.scanCan).apply {
            visibility = if (hasCamera) View.VISIBLE else View.GONE
            setOnClickListener { launchScan(ScanActivity.MODE_CAN) }
        }
        // Sans caméra, la saisie manuelle est le chemin nominal -> dépliée d'office.
        if (!hasCamera) manualGroup.visibility = View.VISIBLE

        manualToggle.setOnClickListener {
            manualGroup.visibility = if (manualGroup.isVisible) View.GONE else View.VISIBLE
        }
        findViewById<MaterialButton>(R.id.rescan).setOnClickListener { resetToScan() }

        findViewById<MaterialButton>(R.id.help).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.help).setMessage(R.string.help_text)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        findViewById<View>(R.id.privacyRow).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_title).setMessage(R.string.privacy_text)
                .setPositiveButton(android.R.string.ok, null).show()
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        showIdle()
    }

    private fun launchScan(mode: String) {
        scanLauncher.launch(
            Intent(this, ScanActivity::class.java)
                .putExtra(ScanActivity.EXTRA_MODE, mode)
                .putExtra(ScanActivity.EXTRA_DIAG, diagMode)   // bandeau OCR live si diag actif
        )
    }

    /**
     * MRZ détectée : étape 2. On ne montre QUE ce qui sert au conseiller à cet instant —
     * le document reconnu et l'invite NFC ; la carte de scan et l'aide disparaissent.
     */
    private fun onMrzScanned(mrz: MrzOcr.MrzData) {
        scanned = mrz
        mrzFailures = 0
        mrzOffset = 0
        readAttempt = 0
        // Variantes à itérer si la clé MRZ échoue (carte d'identité uniquement, cf. openWithMrz).
        mrzCandidateCount = if (mrz.docType == MrzOcr.DocType.ID_CARD)
            MrzKeyCandidates.documentNumberCandidates(mrz.documentNumber).size else 1
        diagCollector = null   // nouvelle carte -> nouveau collecteur de diagnostic
        resultCard.visibility = View.GONE
        diagCard.visibility = View.GONE
        scanCard.visibility = View.GONE
        helpBtn.visibility = View.GONE
        val isId = mrz.docType == MrzOcr.DocType.ID_CARD
        val typeLabel = when (mrz.docType) {
            MrzOcr.DocType.PASSPORT -> R.string.doc_passport
            MrzOcr.DocType.ID_CARD -> R.string.doc_id_card
            MrzOcr.DocType.RESIDENCE_PERMIT -> R.string.doc_residence
        }
        detectedType.text = getString(
            R.string.detected_summary,
            getString(typeLabel),
            mrz.issuingState.ifBlank { "?" },
        )
        detectedFields.text = buildString {
            appendLine("Doc : ${mrz.documentNumber}")
            append("Naissance : ${mrz.dateOfBirth}   Expiration : ${mrz.dateOfExpiry}")
        }
        setTextBlur(detectedFields, blurred = true) // données masquées par défaut
        setChip(chipMrzValid, true, R.string.mrz_valid, R.string.mrz_valid, false)
        // Clé MRZ d'abord pour TOUS les documents : les cartes d'identité (CNIe française,
        // CNIE marocaine…) acceptent PACE-MRZ comme les passeports. Le CAN n'apparaît
        // qu'en repli si la puce refuse la clé MRZ (cf. onReadFailure).
        canFallback = false
        canInput.text = null
        canGroup.visibility = View.GONE
        // Invite claire à approcher la puce NFC (étape 2).
        nfcPromptText.setText(R.string.nfc_invite_mrz)
        nfcPrompt.visibility = View.VISIBLE
        detectedCard.visibility = View.VISIBLE
        showTapStatus()
        statusSetByScan = true
    }

    private fun resetToScan() {
        scanned = null
        canFallback = false
        mrzFailures = 0
        mrzOffset = 0
        mrzCandidateCount = 1
        readAttempt = 0
        diagCollector = null
        lastReport = null
        detectedCard.visibility = View.GONE
        resultCard.visibility = View.GONE
        readingCard.visibility = View.GONE
        diagCard.visibility = View.GONE
        scanCard.visibility = View.VISIBLE
        helpBtn.visibility = View.VISIBLE
        canInput.text = null
        showIdle()
        statusSetByScan = true
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        when {
            adapter == null -> warn(getString(R.string.no_nfc))
            !adapter.isEnabled -> {
                warn(getString(R.string.nfc_disabled))
                status.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }
            else -> {
                status.setOnClickListener(null)
                status.isClickable = false
                if (!resultCard.isVisible && !statusSetByScan) {
                    if (scanned != null) showTapStatus() else showIdle()
                }
                statusSetByScan = false
                val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getActivity(this, 0, intent, flags)
                val filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED))
                val techLists = arrayOf(arrayOf(IsoDep::class.java.name))
                adapter.enableForegroundDispatch(this, pending, filters, techLists)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (readJob?.isActive == true) return

        val tag: Tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        val isoDep = IsoDep.get(tag) ?: run { warn(getString(R.string.not_isodep)); return }

        val req = buildRequest() ?: return

        readAttempt++
        showReading()
        resultCard.visibility = View.GONE
        // Un collecteur par carte (réutilisé de l'échec MRZ au repli CAN), seulement en
        // mode diagnostic -> zéro impact quand il est inactif.
        if (diagMode && diagCollector == null) diagCollector = DiagnosticsCollector()
        val collector = diagCollector
        // Rotation de protocole : après un premier échec de lecture MRZ (souvent un gel de
        // PACE-GM), on tente PACE-IM en tête au tap suivant — certaines cartes ne lisent
        // qu'en IM. Chaque tap est une connexion NFC fraîche.
        val preferIm = mrzFailures >= 1
        lastPreferIm = preferIm
        val candidateOffset = mrzOffset
        readJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                CnieReader().read(
                    isoDep, req.key, req.expectedMrz, cscaCerts.await(),
                    onStep = { step -> runOnUiThread { showReadStep(step) } },
                    diag = collector,
                    preferIm = preferIm,
                    mrzCandidateOffset = candidateOffset,
                )
            }
            withContext(Dispatchers.Main) {
                result
                    .onSuccess { showResult(it) }
                    .onFailure { onReadFailure(it, req) }
            }
        }
    }

    private fun showReadStep(step: ReadStep) = renderReadStep(step)

    /**
     * Rendu de l'étape de lecture. Le handshake PACE (CONNECT) peut durer ~6 s PAR TENTATIVE et
     * peut être retenté (rotation IM, itération des variantes du numéro) : on affiche alors une
     * barre INDÉTERMINÉE animée + le n° d'essai — l'utilisateur voit qu'une lecture est en cours
     * (et laquelle), au lieu d'une barre figée. Les étapes suivantes sont déterministes.
     */
    private fun renderReadStep(step: ReadStep) {
        if (step == ReadStep.CONNECT) {
            setReadIndeterminate(true)
            readStep.text = if (readAttempt > 1)
                getString(R.string.reading_connect_attempt, readAttempt)
            else getString(R.string.reading_connect)
            return
        }
        setReadIndeterminate(false)
        readProgress.setProgressCompat(step.percent, true)
        readStep.setText(
            when (step) {
                ReadStep.CONNECT -> R.string.reading_connect
                ReadStep.IDENTITY -> R.string.reading_identity
                ReadStep.PHOTO -> R.string.reading_photo
                ReadStep.SECURITY -> R.string.reading_security
                ReadStep.VERIFY -> R.string.reading_verify
            },
        )
    }

    /** Bascule la barre déterminée <-> indéterminée sans exception (cycle de visibilité). */
    private fun setReadIndeterminate(indeterminate: Boolean) {
        if (readProgress.isIndeterminate == indeterminate) return
        readProgress.visibility = View.INVISIBLE
        readProgress.isIndeterminate = indeterminate
        readProgress.visibility = View.VISIBLE
    }

    /**
     * Échec de lecture. Deux natures d'échec :
     *  - refus de clé avéré ([ChipAccessException], SW 6300) : la clé MRZ est fausse ;
     *  - aléa transitoire (perte de contact, TagLost) : la carte a « décroché ».
     *
     * Pour une carte d'identité (qui a un CAN), on propose le repli CAN dès un refus avéré,
     * MAIS AUSSI après quelques échecs transitoires RÉPÉTÉS : certaines cartes échouent
     * durablement en PACE-MRZ (ex. PACE-GM brainpool qui time-out en TagLost à chaque essai)
     * et le CAN est alors le seul chemin qui fonctionne — il ne doit pas rester inaccessible.
     * Un aléa unique invite simplement à re-présenter la carte, sans basculer sur le CAN.
     */
    private fun onReadFailure(e: Throwable, req: AccessRequest) {
        readingCard.visibility = View.GONE
        // L'invite NFC redevient utile : il faut représenter le document.
        if (scanned != null) nfcPrompt.visibility = View.VISIBLE

        val keyRefused = e is ChipAccessException
        if (req.key is AccessKey.Mrz) {
            mrzFailures++   // compteur d'échecs MRZ de la carte courante
            // Itération des variantes du numéro (paires aveugles) :
            //  - refus net (6300) : tous les candidats depuis l'offset ont été essayés dans le
            //    tap (la puce refuse proprement) -> variantes épuisées, on passera au CAN.
            //  - gel en IM : ce candidat est mauvais -> candidat suivant au prochain tap.
            //  - gel en GM (1er essai) : on ne bouge PAS -> IM réessaiera le MÊME candidat.
            when {
                keyRefused -> mrzOffset = mrzCandidateCount
                lastPreferIm -> mrzOffset++
            }
        }

        // Classification finale pour le rapport : refus avéré vs aléa transitoire.
        diagCollector?.apply {
            classification = if (keyRefused) "refus avéré (6300)" else "transitoire"
            if (finalAccess == null) finalAccess = "échec"
        }
        assembleReport(chip = null)

        val card = scanned
        // Document TD1 (carte d'identité / titre de séjour) : il a un CAN. Le code document
        // n'a aucun chiffre de contrôle, donc on ne se fie pas au type exact — tout ce qui
        // n'est pas un passeport peut avoir un CAN.
        val hasCan = req.key is AccessKey.Mrz && card != null && card.docType != MrzOcr.DocType.PASSPORT
        when {
            hasCan -> {
                // Échec de lecture MRZ sur une carte d'identité. Le CAN (recto) est une clé
                // alternative FIABLE : 6 chiffres imprimés, sans ambiguïté de glyphe — là où
                // un n° de document peut porter des paires aveugles (G/6, L/1…) qui rendent la
                // clé PACE-MRZ incertaine et font GELER certaines puces. On le propose DÈS le
                // premier échec, sans bloquer une nouvelle tentative MRZ : tant que le CAN
                // n'est pas saisi, buildRequest retente la MRZ en itérant les variantes du
                // numéro, puis, une fois épuisées, attend le CAN pour ne plus boucler.
                canFallback = true
                canGroup.visibility = View.VISIBLE
                nfcPromptText.setText(R.string.can_fallback)
                nfcPrompt.visibility = View.VISIBLE
                warn(getString(if (keyRefused) R.string.access_denied_mrz_id else R.string.can_after_retries))
            }
            req.key is AccessKey.Can -> warn(getString(R.string.access_denied_can))
            keyRefused -> warn(getString(R.string.access_denied_mrz))   // passeport : pas de CAN
            else -> warn(getString(R.string.read_interrupted))          // aléa unique : re-présenter
        }
    }

    // ---- Rapport de diagnostic technique caviardé ----

    /** Assemble le rapport (caviardé à la construction) et l'affiche si le mode est actif. */
    private fun assembleReport(chip: ReadResult?) {
        val collector = diagCollector ?: return
        lastReport = DebugReports.build(
            env = buildEnv(),
            scanned = scanned,
            chip = chip,
            diag = collector,
            salt = corrSalt,
            includeExpiryYear = INCLUDE_EXPIRY_YEAR,
        )
        maybeShowDiagnostics()
    }

    private fun buildEnv(): DebugEnv {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "?"
        return DebugEnv(
            appVersion = version,
            commit = BuildConfig.GIT_COMMIT,
            timestampIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            ocrEngine = OCR_ENGINE,
            analysisResolution = scanOcrRes,
            rotationDegrees = scanOcrRot,
            libs = LIBS,
        )
    }

    private fun maybeShowDiagnostics() {
        val report = lastReport
        if (diagMode && report != null) {
            diagText.text = report.serialize()
            diagCard.visibility = View.VISIBLE
        } else {
            diagCard.visibility = View.GONE
        }
    }

    private fun toggleDiagMode() {
        diagMode = !diagMode
        diagPrefs().edit().putBoolean(PREF_DIAG_MODE, diagMode).apply()
        Toast.makeText(this, if (diagMode) R.string.diag_on else R.string.diag_off, Toast.LENGTH_SHORT).show()
        maybeShowDiagnostics()
    }

    private fun toggleDiagDetails() {
        val show = diagText.visibility != View.VISIBLE
        diagText.visibility = if (show) View.VISIBLE else View.GONE
        diagActions.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun copyReport() {
        val text = lastReport?.serialize() ?: return
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("veripuce-debug", text))
        Toast.makeText(this, R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val text = lastReport?.serialize() ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.diag_share_subject))
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.diag_share)))
    }

    private fun diagPrefs() = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Sel de corrélation par installation ; ne quitte JAMAIS l'appareil (SHA-256 interne). */
    private fun installSalt(): String {
        val prefs = diagPrefs()
        prefs.getString(PREF_SALT, null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_SALT, salt).apply()
        return salt
    }

    private companion object {
        const val PREF_NAME = "veripuce_diag"
        const val PREF_DIAG_MODE = "diag_mode"
        const val PREF_SALT = "corr_salt"
        const val INCLUDE_EXPIRY_YEAR = true
        const val OCR_ENGINE = "tesseract 5 LSTM + modèle OCR-B/MRZ (bundled)"
        const val LIBS = "JMRTD 0.8.6, scuba-sc-android 0.0.26, Tesseract4Android 4.9.0, BouncyCastle 1.84"
    }

    private data class AccessRequest(val key: AccessKey, val expectedMrz: MrzOcr.MrzData?)

    /** Construit la requête d'accès à partir de l'état (scan ou saisie manuelle). */
    private fun buildRequest(): AccessRequest? {
        val mrz = scanned
        if (mrz != null) {
            if (mrz.docType != MrzOcr.DocType.PASSPORT && canFallback) {
                // Repli proposé. Si le CAN (recto) est saisi, on l'utilise — clé FIABLE, sans
                // ambiguïté de glyphe. Sinon on NE bloque pas tout de suite : on retente la clé
                // MRZ en itérant les VARIANTES du numéro (une par tap) tant qu'il en reste, PUIS
                // on attend le CAN pour ne plus boucler sur une puce qui gèle en PACE-MRZ.
                val can = canInput.text?.toString()?.trim().orEmpty()
                if (can.length == 6 && can.all { it.isDigit() }) {
                    return AccessRequest(AccessKey.Can(can), mrz)
                }
                if (mrzOffset >= mrzCandidateCount) {
                    warn(getString(R.string.enter_can)); return null   // variantes épuisées
                }
                // sinon : nouvelle tentative MRZ (variante mrzOffset ci-dessous), CAN proposé.
            }
            // Cas nominal, tous documents : clé dérivée de la MRZ (PACE-MRZ, repli BAC).
            // Les CNIe l'acceptent aussi (applet « PACE passwords: MRZ, CAN, PIN, PUK »).
            return AccessRequest(AccessKey.Mrz(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry), mrz)
        }

        // Saisie manuelle (aucun scan) : le type choisi (onglets) détermine la clé —
        // carte d'identité -> CAN ; passeport / titre de séjour -> n° + dates (clé MRZ).
        if (manualType.checkedButtonId == R.id.typeCard) {
            val can = manualCan.text?.toString()?.trim().orEmpty()
            if (can.length != 6 || !can.all { it.isDigit() }) {
                warn(getString(R.string.enter_can))
                return null
            }
            return AccessRequest(AccessKey.Can(can), null)
        }
        val doc = docInput.text?.toString()?.trim()?.uppercase().orEmpty()
        val dob = dobInput.text?.toString()?.trim().orEmpty()
        val exp = expInput.text?.toString()?.trim().orEmpty()
        val datesOk = dob.length == 6 && dob.all { it.isDigit() } && exp.length == 6 && exp.all { it.isDigit() }
        if (doc.isNotEmpty() && datesOk) {
            val manualMrz = MrzOcr.MrzData(doc, dob, exp, MrzOcr.DocType.PASSPORT, "")
            return AccessRequest(AccessKey.Mrz(doc, dob, exp), manualMrz)
        }
        warn(getString(R.string.enter_mrz))
        return null
    }

    /** Onglets de saisie manuelle : carte -> champ CAN ; passeport / séjour -> champs MRZ. */
    private fun updateManualFields() {
        val isCard = manualType.checkedButtonId == R.id.typeCard
        manualCanGroup.visibility = if (isCard) View.VISIBLE else View.GONE
        manualMrzGroup.visibility = if (isCard) View.GONE else View.VISIBLE
    }

    // ---- Masquage des données sensibles (MRZ scannée et données de la puce) ----

    /** Floute (ou défloute) le rendu d'un TextView sans toucher à son contenu. */
    private fun setTextBlur(tv: TextView, blurred: Boolean) {
        if (blurred) {
            tv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            tv.paint.maskFilter = BlurMaskFilter(tv.textSize / 2.2f, BlurMaskFilter.Blur.NORMAL)
        } else {
            tv.paint.maskFilter = null
        }
        tv.invalidate()
    }

    /** Flou par sous-échantillonnage (compatible toutes versions d'Android). */
    private fun blurredCopy(src: Bitmap): Bitmap {
        val small = Bitmap.createScaledBitmap(
            src,
            (src.width / 14).coerceAtLeast(1),
            (src.height / 14).coerceAtLeast(1),
            true,
        )
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    /** Applique ou lève le masquage sur toutes les vues sensibles. */
    private fun setSensitiveVisible(visible: Boolean) {
        setTextBlur(nameView, !visible)
        setTextBlur(fields, !visible)
        setTextBlur(extra, !visible)
        setTextBlur(detectedFields, !visible)
        (if (visible) photoOriginal else photoBlurred)?.let { photo.setImageBitmap(it) }
    }

    /** Révélation en appui maintenu : visible tant que le doigt reste posé. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindHoldToReveal(trigger: View) {
        trigger.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> setSensitiveVisible(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    setSensitiveVisible(false)
                }
            }
            true
        }
    }

    private fun showResult(r: ReadResult) {
        // Étape 3 : ne laisser à l'écran QUE le verdict + la carte résultat (et le bouton
        // « Nouveau contrôle ») — tout le reste est du bruit pour le conseiller.
        readingCard.visibility = View.GONE
        nfcPrompt.visibility = View.GONE
        detectedCard.visibility = View.GONE
        scanCard.visibility = View.GONE
        helpBtn.visibility = View.GONE
        nameView.text = "${r.surname} ${r.givenNames}".trim()
        expiredBadge.visibility = if (isExpired(r.dateOfExpiry)) View.VISIBLE else View.GONE

        // Informations en clair (dates JJ/MM/AAAA, libellés courants).
        fields.text = buildString {
            appendLine("${getString(R.string.lbl_born)} : ${fmtDate(r.dateOfBirth, expiry = false)}")
            appendLine("${getString(R.string.lbl_expires)} : ${fmtDate(r.dateOfExpiry, expiry = true)}")
            appendLine("${getString(R.string.lbl_nationality)} : ${r.nationality}")
            append("${getString(R.string.lbl_docnum)} : ${r.documentNumber}")
            r.dg13?.heightCm?.let { append("\nTaille : $it cm") }
        }
        val extraText = buildString {
            r.dg13?.address?.let { a ->
                val ligne = listOfNotNull(a.street, a.postalCode, a.city, a.country).joinToString(", ")
                if (ligne.isNotEmpty()) appendLine("Adresse : $ligne")
            }
            r.dg13?.birthPlace?.let { b ->
                val lieu = listOfNotNull(b.city, b.department).joinToString(", ")
                if (lieu.isNotEmpty()) append("Lieu de naissance : $lieu")
            }
        }.trim()
        extra.text = extraText
        extra.visibility = if (extraText.isEmpty()) View.GONE else View.VISIBLE

        // Vérifications en langage clair (grande pastille verte/rouge + libellé).
        setCheck(integrityIcon, integrityLabel, r.hashesMatchSod, R.string.check_integrity_ok, R.string.check_integrity_bad)
        when (r.mrzMatchesScan) {
            null -> rowConsistency.visibility = View.GONE // non pertinent pour un accès MRZ
            else -> {
                rowConsistency.visibility = View.VISIBLE
                setCheck(consistencyIcon, consistencyLabel, r.mrzMatchesScan, R.string.check_consistency_ok, R.string.check_consistency_bad)
            }
        }
        when (r.cloneCheck) {
            CloneCheck.AUTHENTIC -> { rowClone.visibility = View.VISIBLE; setCheck(cloneIcon, cloneLabel, true, R.string.check_clone_ok, R.string.check_clone_ok) }
            CloneCheck.FAILED -> { rowClone.visibility = View.VISIBLE; setCheck(cloneIcon, cloneLabel, false, R.string.check_clone_bad, R.string.check_clone_bad) }
            CloneCheck.UNSUPPORTED -> rowClone.visibility = View.GONE
        }
        // Origine : signature du SOD + chaîne jusqu'à une CSCA de confiance.
        when (r.signature) {
            SignatureCheck.TRUSTED -> setOrigin(R.drawable.ic_state_ok, R.color.on_ok_container, R.string.check_origin_ok, muted = false)
            SignatureCheck.INVALID -> setOrigin(R.drawable.ic_state_error, R.color.on_bad_container, R.string.check_origin_invalid, muted = false)
            SignatureCheck.VALID_UNTRUSTED -> setOrigin(R.drawable.ic_state_info, R.color.on_neutral_container, R.string.check_origin_untrusted, muted = true)
            SignatureCheck.NOT_CHECKED -> setOrigin(R.drawable.ic_state_info, R.color.on_neutral_container, R.string.check_origin_pending, muted = true)
        }

        photo.visibility = if (r.photo != null) View.VISIBLE else View.GONE
        photoOriginal = r.photo
        photoBlurred = r.photo?.let(::blurredCopy)
        // Données sensibles (photo, identité, n° document) floutées par défaut ;
        // visibles uniquement tant que « Maintenir pour afficher » est enfoncé.
        setSensitiveVisible(false)

        resultCard.visibility = View.VISIBLE

        // Verdict global honnête à 3 états :
        //  ROUGE = anomalie dure (dont signature invalide) ; VERT = uniquement si l'origine
        //  étatique est prouvée (DSC -> CSCA de confiance) ; NEUTRE = contrôles internes OK.
        val hardFail = !r.hashesMatchSod || r.mrzMatchesScan == false ||
            r.cloneCheck == CloneCheck.FAILED || r.signature == SignatureCheck.INVALID
        when {
            hardFail -> setStatus(getString(R.string.result_fail), R.drawable.ic_state_error, R.color.on_bad_container)
            r.signature == SignatureCheck.TRUSTED -> setStatus(getString(R.string.result_trusted), R.drawable.ic_state_ok, R.color.on_ok_container)
            else -> setStatus(getString(R.string.result_unverified), R.drawable.ic_state_info, R.color.on_neutral_container)
        }
        // Le détail des vérifications vit avec le verdict, dans le même bandeau.
        checksContainer.visibility = View.VISIBLE

        diagCollector?.classification = "succès"
        assembleReport(chip = r)
    }

    /** Ligne « origine » : icône teintée + libellé (couleur atténuée pour les états non définitifs). */
    private fun setOrigin(@DrawableRes icon: Int, @ColorRes tint: Int, @StringRes text: Int, muted: Boolean) {
        originIcon.setImageResource(icon)
        originIcon.setColorFilter(ContextCompat.getColor(this, tint))
        originLabel.setText(text)
        originLabel.setTextColor(
            com.google.android.material.color.MaterialColors.getColor(
                originLabel,
                if (muted) com.google.android.material.R.attr.colorOnSurfaceVariant
                else com.google.android.material.R.attr.colorOnSurface,
            ),
        )
    }

    // --- Statut (carte masquée au repos/détecté : les cartes de scan/détection portent le message) ---

    private fun showIdle() { statusCard.visibility = View.GONE }

    private fun showTapStatus() { statusCard.visibility = View.GONE }

    private fun warn(text: String) = setStatus(text, R.drawable.ic_state_error, R.color.on_bad_container)

    /** Contact NFC établi : indicateur bien visible, l'invite et le statut s'effacent. */
    private fun showReading() {
        statusCard.visibility = View.GONE
        nfcPrompt.visibility = View.GONE
        readingCard.visibility = View.VISIBLE
        renderReadStep(ReadStep.CONNECT)
    }

    private fun setStatus(text: String, @DrawableRes icon: Int, @ColorRes tint: Int) {
        statusCard.visibility = View.VISIBLE
        progress.visibility = View.GONE
        statusIcon.visibility = View.VISIBLE
        statusIcon.setImageResource(icon)
        statusIcon.setColorFilter(ContextCompat.getColor(this, tint))
        status.text = text
        // Le détail des vérifications n'accompagne que le verdict final (showResult le rouvre).
        checksContainer.visibility = View.GONE
    }

    /** Ligne de vérification : grande pastille verte (OK) ou rouge (échec) + libellé clair. */
    private fun setCheck(icon: ImageView, label: TextView, ok: Boolean, @StringRes okText: Int, @StringRes badText: Int) {
        icon.setImageResource(if (ok) R.drawable.ic_state_ok else R.drawable.ic_state_error)
        icon.setColorFilter(ContextCompat.getColor(this, if (ok) R.color.on_ok_container else R.color.on_bad_container))
        label.setText(if (ok) okText else badText)
    }

    /** AAMMJJ -> JJ/MM/AAAA (siècle inféré : naissance dans le passé, expiration en 20xx). */
    private fun fmtDate(yymmdd: String, expiry: Boolean): String {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return yymmdd
        val yy = yymmdd.substring(0, 2).toInt()
        val curYY = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) % 100
        val year = if (expiry) 2000 + yy else if (yy > curYY) 1900 + yy else 2000 + yy
        return "%s/%s/%d".format(yymmdd.substring(4, 6), yymmdd.substring(2, 4), year)
    }

    private fun isExpired(yymmdd: String): Boolean {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return false
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.YEAR) * 10000 + (cal.get(java.util.Calendar.MONTH) + 1) * 100 + cal.get(java.util.Calendar.DAY_OF_MONTH)
        val exp = (2000 + yymmdd.substring(0, 2).toInt()) * 10000 + yymmdd.substring(2, 4).toInt() * 100 + yymmdd.substring(4, 6).toInt()
        return exp < today
    }

    /** Colore un chip : vert (OK), rouge (échec) ou neutre (indéterminé). */
    private fun setChip(chip: Chip, ok: Boolean, @StringRes okText: Int, @StringRes koText: Int, neutralIfNotOk: Boolean) {
        val (bg, fg, text) = when {
            ok -> Triple(R.color.ok_container, R.color.on_ok_container, okText)
            neutralIfNotOk -> Triple(R.color.neutral_container, R.color.on_neutral_container, koText)
            else -> Triple(R.color.bad_container, R.color.on_bad_container, koText)
        }
        chip.text = getString(text)
        chip.chipBackgroundColor = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, bg))
        chip.setTextColor(ContextCompat.getColor(this, fg))
    }
}
