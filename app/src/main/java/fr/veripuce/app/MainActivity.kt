package fr.veripuce.app

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
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
import java.security.Security

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

    /** Statut posé par le retour de scan : ne pas l'écraser au onResume qui suit. */
    private var statusSetByScan = false

    private lateinit var scanMrz: MaterialButton
    private lateinit var manualToggle: MaterialButton
    private lateinit var manualGroup: View
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
    private lateinit var status: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var progress: CircularProgressIndicator

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
                res.resultCode == RESULT_OK && data.getStringExtra(ScanActivity.EXTRA_MODE) == ScanActivity.MODE_MRZ ->
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
        chipMrzValid = findViewById(R.id.chipMrzValid)
        canGroup = findViewById(R.id.canGroup)
        canInput = findViewById(R.id.canInput)
        nfcPrompt = findViewById(R.id.nfcPrompt)
        nfcPromptText = findViewById(R.id.nfcPromptText)
        statusCard = findViewById(R.id.statusCard)
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
            Intent(this, ScanActivity::class.java).putExtra(ScanActivity.EXTRA_MODE, mode)
        )
    }

    /** MRZ détectée : afficher le document reconnu et l'étape suivante (puce). */
    private fun onMrzScanned(mrz: MrzOcr.MrzData) {
        scanned = mrz
        resultCard.visibility = View.GONE
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
        detectedCard.visibility = View.GONE
        resultCard.visibility = View.GONE
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

        showReading()
        resultCard.visibility = View.GONE
        readJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { CnieReader().read(isoDep, req.key, req.expectedMrz, cscaCerts.await()) }
            withContext(Dispatchers.Main) {
                result
                    .onSuccess { showResult(it) }
                    .onFailure { onReadFailure(it, req) }
            }
        }
    }

    /**
     * Échec de lecture. Cas particulier : une carte d'identité qui refuse la clé MRZ
     * (rare — les CNIe acceptent normalement PACE-MRZ) -> on fait apparaître le champ
     * CAN et on invite à réessayer. Une perte de contact NFC ne passe PAS par ici
     * (voir [ChipAccessException]).
     */
    private fun onReadFailure(e: Throwable, req: AccessRequest) {
        if (e is ChipAccessException) {
            when {
                req.key is AccessKey.Mrz && scanned?.docType == MrzOcr.DocType.ID_CARD -> {
                    canFallback = true
                    canGroup.visibility = View.VISIBLE
                    nfcPromptText.setText(R.string.can_fallback)
                    nfcPrompt.visibility = View.VISIBLE
                    warn(getString(R.string.access_denied_mrz_id))
                }
                req.key is AccessKey.Can -> warn(getString(R.string.access_denied_can))
                else -> warn(getString(R.string.access_denied_mrz))
            }
            return
        }
        warn(getString(R.string.read_error, e.message ?: "?"))
    }

    private data class AccessRequest(val key: AccessKey, val expectedMrz: MrzOcr.MrzData?)

    /** Construit la requête d'accès à partir de l'état (scan ou saisie manuelle). */
    private fun buildRequest(): AccessRequest? {
        val mrz = scanned
        if (mrz != null) {
            if (mrz.docType == MrzOcr.DocType.ID_CARD && canFallback) {
                // Repli : la puce a refusé la clé MRZ -> le CAN (recto) devient requis.
                val can = canInput.text?.toString()?.trim().orEmpty()
                return if (can.length != 6 || !can.all { it.isDigit() }) {
                    warn(getString(R.string.enter_can)); null
                } else {
                    AccessRequest(AccessKey.Can(can), mrz)
                }
            }
            // Cas nominal, tous documents : clé dérivée de la MRZ (PACE-MRZ, repli BAC).
            // Les CNIe l'acceptent aussi (applet « PACE passwords: MRZ, CAN, PIN, PUK »).
            return AccessRequest(AccessKey.Mrz(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry), mrz)
        }

        // Saisie manuelle (aucun scan) : CAN -> CNIe ; sinon doc/naissance/expiration -> MRZ.
        val can = manualCan.text?.toString()?.trim().orEmpty()
        if (can.length == 6 && can.all { it.isDigit() }) {
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
        warn(getString(R.string.scan_first))
        return null
    }

    private fun showResult(r: ReadResult) {
        nfcPrompt.visibility = View.GONE // lecture faite : l'invite NFC n'a plus lieu d'être
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
        r.photo?.let { photo.setImageBitmap(it) }

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

    private fun showReading() {
        statusCard.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        status.text = getString(R.string.reading)
    }

    private fun setStatus(text: String, @DrawableRes icon: Int, @ColorRes tint: Int) {
        statusCard.visibility = View.VISIBLE
        progress.visibility = View.GONE
        statusIcon.visibility = View.VISIBLE
        statusIcon.setImageResource(icon)
        statusIcon.setColorFilter(ContextCompat.getColor(this, tint))
        status.text = text
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
