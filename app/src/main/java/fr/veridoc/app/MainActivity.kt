package fr.veridoc.app

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.Security

class MainActivity : AppCompatActivity() {

    private enum class Mode { ID, PASSPORT }

    private var nfcAdapter: NfcAdapter? = null
    private var mode: Mode = Mode.ID
    private var readJob: Job? = null

    /** Statut posé par le retour de scan : ne pas l'écraser au onResume qui suit. */
    private var statusSetByScan = false

    /** Retour du scan OCR : pré-remplit les champs (la saisie manuelle reste possible). */
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data ?: return@registerForActivityResult
            if (res.resultCode == RESULT_OK) {
                when (data.getStringExtra(ScanActivity.EXTRA_MODE)) {
                    ScanActivity.MODE_CAN ->
                        canInput.setText(data.getStringExtra(ScanActivity.EXTRA_CAN))
                    ScanActivity.MODE_MRZ -> {
                        docInput.setText(data.getStringExtra(ScanActivity.EXTRA_DOC))
                        dobInput.setText(data.getStringExtra(ScanActivity.EXTRA_DOB))
                        expInput.setText(data.getStringExtra(ScanActivity.EXTRA_EXP))
                    }
                }
                resultCard.visibility = View.GONE // l'ancien résultat ne correspond plus
                setStatus(getString(R.string.scan_filled), R.drawable.ic_state_ok, R.color.on_ok_container)
                statusSetByScan = true
            } else if (data.getBooleanExtra(ScanActivity.EXTRA_CAMERA_UNAVAILABLE, false)) {
                warn(getString(R.string.camera_unavailable)); statusSetByScan = true
            } else if (data.getBooleanExtra(ScanActivity.EXTRA_PERMISSION_DENIED, false)) {
                val blocked = data.getBooleanExtra(ScanActivity.EXTRA_PERMISSION_PERMANENT, false)
                warn(getString(if (blocked) R.string.camera_blocked else R.string.camera_denied))
                statusSetByScan = true
            }
        }

    private lateinit var modeGroup: MaterialButtonToggleGroup
    private lateinit var groupCan: View
    private lateinit var groupMrz: View
    private lateinit var canInput: TextInputEditText
    private lateinit var docInput: TextInputEditText
    private lateinit var dobInput: TextInputEditText
    private lateinit var expInput: TextInputEditText

    private lateinit var status: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var progress: CircularProgressIndicator

    private lateinit var resultCard: MaterialCardView
    private lateinit var photo: ShapeableImageView
    private lateinit var nameView: TextView
    private lateinit var fields: TextView
    private lateinit var extra: TextView
    private lateinit var chipIntegrity: Chip
    private lateinit var chipSignature: Chip

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge (targetSdk 35+) : décaler le contenu des barres système/encoche.
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

        modeGroup = findViewById(R.id.modeGroup)
        groupCan = findViewById(R.id.groupCan)
        groupMrz = findViewById(R.id.groupMrz)
        canInput = findViewById(R.id.canInput)
        docInput = findViewById(R.id.docInput)
        dobInput = findViewById(R.id.dobInput)
        expInput = findViewById(R.id.expInput)
        status = findViewById(R.id.status)
        statusIcon = findViewById(R.id.statusIcon)
        progress = findViewById(R.id.progress)
        resultCard = findViewById(R.id.resultCard)
        photo = findViewById(R.id.photo)
        nameView = findViewById(R.id.name)
        fields = findViewById(R.id.fields)
        extra = findViewById(R.id.extra)
        chipIntegrity = findViewById(R.id.chipIntegrity)
        chipSignature = findViewById(R.id.chipSignature)

        modeGroup.check(R.id.btnModeId)
        modeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            applyMode(if (checkedId == R.id.btnModePassport) Mode.PASSPORT else Mode.ID)
        }
        applyMode(Mode.ID)

        // Sans caméra (le manifeste la déclare optionnelle), on masque simplement les
        // boutons de scan : la saisie manuelle est le chemin nominal.
        val hasCamera = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
        findViewById<MaterialButton>(R.id.scanCan).apply {
            visibility = if (hasCamera) View.VISIBLE else View.GONE
            setOnClickListener {
                scanLauncher.launch(
                    Intent(this@MainActivity, ScanActivity::class.java)
                        .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_CAN)
                )
            }
        }
        findViewById<MaterialButton>(R.id.scanMrz).apply {
            visibility = if (hasCamera) View.VISIBLE else View.GONE
            setOnClickListener {
                scanLauncher.launch(
                    Intent(this@MainActivity, ScanActivity::class.java)
                        .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_MRZ)
                )
            }
        }

        findViewById<MaterialButton>(R.id.help).setOnClickListener {
            // Aide dans une boîte de dialogue : ne détruit plus le fil d'état ni le hint.
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.help)
                .setMessage(R.string.help_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        findViewById<View>(R.id.privacyRow).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.privacy_title)
                .setMessage(R.string.privacy_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }

    private fun applyMode(newMode: Mode) {
        mode = newMode
        val passport = newMode == Mode.PASSPORT
        groupCan.visibility = if (passport) View.GONE else View.VISIBLE
        groupMrz.visibility = if (passport) View.VISIBLE else View.GONE
        // Changer de mode ramène à l'état idle (sinon l'ancien résultat resterait affiché).
        resultCard.visibility = View.GONE
        showIdle()
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter
        when {
            adapter == null -> setStatus(getString(R.string.no_nfc), R.drawable.ic_state_error, R.color.on_bad_container)
            !adapter.isEnabled -> {
                setStatus(getString(R.string.nfc_disabled), R.drawable.ic_state_error, R.color.on_bad_container)
                status.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            }
            else -> {
                status.setOnClickListener(null)
                status.isClickable = false
                // Ne pas écraser le message posé par le retour de scan (une seule fois).
                if (resultCard.visibility != View.VISIBLE && !statusSetByScan) showIdle()
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
        // Ignorer tout nouveau tap tant qu'une lecture est en cours : évite deux transceive
        // concurrents sur le même canal IsoDep et l'écrasement d'un résultat déjà affiché.
        if (readJob?.isActive == true) return

        val tag: Tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        val isoDep = IsoDep.get(tag) ?: run {
            setStatus(getString(R.string.not_isodep), R.drawable.ic_state_error, R.color.on_bad_container); return
        }

        val key = buildAccessKey() ?: return

        showReading()
        resultCard.visibility = View.GONE

        readJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching { CnieReader().read(isoDep, key) }
            withContext(Dispatchers.Main) {
                result
                    .onSuccess { showResult(it) }
                    .onFailure {
                        setStatus(
                            getString(R.string.read_error, it.message ?: "?"),
                            R.drawable.ic_state_error,
                            R.color.on_bad_container,
                        )
                    }
            }
        }
    }

    /** Construit la clé d'accès selon le mode, ou affiche une erreur de saisie et renvoie null. */
    private fun buildAccessKey(): AccessKey? = when (mode) {
        Mode.ID -> {
            val can = canInput.text?.toString()?.trim().orEmpty()
            if (can.length != 6 || !can.all { it.isDigit() }) {
                warn(getString(R.string.enter_can)); null
            } else {
                AccessKey.Can(can)
            }
        }

        Mode.PASSPORT -> {
            val doc = docInput.text?.toString()?.trim()?.uppercase().orEmpty()
            val dob = dobInput.text?.toString()?.trim().orEmpty()
            val exp = expInput.text?.toString()?.trim().orEmpty()
            val datesOk = dob.length == 6 && dob.all { it.isDigit() } &&
                exp.length == 6 && exp.all { it.isDigit() }
            if (doc.isEmpty() || !datesOk) {
                warn(getString(R.string.enter_mrz)); null
            } else {
                AccessKey.Mrz(doc, dob, exp)
            }
        }
    }

    private fun showResult(r: ReadResult) {
        nameView.text = "${r.surname} ${r.givenNames}".trim()

        // Champs à caractères fixes en monospace (alignement des chiffres).
        fields.text = buildString {
            appendLine("Doc : ${r.documentNumber}    Nat : ${r.nationality}")
            append("Naissance : ${r.dateOfBirth}")
            r.dg13?.heightCm?.let { append("\nTaille : $it cm") }
        }

        // Texte libre (adresse, lieu de naissance) en police proportionnelle, lisible.
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

        setChip(
            chipIntegrity,
            ok = r.hashesMatchSod,
            okText = R.string.chip_integrity_ok,
            koText = R.string.chip_integrity_bad,
            neutralIfNotOk = false,
        )
        setChip(
            chipSignature,
            ok = r.sodSignatureVerified,
            okText = R.string.chip_sig_ok,
            koText = R.string.chip_sig_unverified,
            neutralIfNotOk = true, // non vérifiée != invalide (trust store CSCA non branché)
        )

        photo.visibility = if (r.photo != null) View.VISIBLE else View.GONE
        r.photo?.let { photo.setImageBitmap(it) }

        resultCard.visibility = View.VISIBLE
        val allOk = r.hashesMatchSod
        setStatus(
            getString(R.string.done),
            if (allOk) R.drawable.ic_state_ok else R.drawable.ic_state_error,
            if (allOk) R.color.on_ok_container else R.color.on_bad_container,
        )
    }

    // --- Helpers d'état de la carte de statut ---

    private fun showIdle() = setStatus(
        getString(if (mode == Mode.PASSPORT) R.string.hint_passport else R.string.hint_id),
        R.drawable.ic_state_info,
        R.color.on_neutral_container,
    )

    private fun warn(text: String) =
        setStatus(text, R.drawable.ic_state_error, R.color.on_bad_container)

    private fun showReading() {
        progress.visibility = View.VISIBLE
        statusIcon.visibility = View.GONE
        status.text = getString(R.string.reading)
    }

    private fun setStatus(text: String, @DrawableRes icon: Int, @ColorRes tint: Int) {
        progress.visibility = View.GONE
        statusIcon.visibility = View.VISIBLE
        statusIcon.setImageResource(icon)
        statusIcon.setColorFilter(ContextCompat.getColor(this, tint))
        status.text = text
    }

    /** Colore un chip : vert (OK), rouge (échec) ou neutre (état indéterminé). */
    private fun setChip(chip: Chip, ok: Boolean, @androidx.annotation.StringRes okText: Int, @androidx.annotation.StringRes koText: Int, neutralIfNotOk: Boolean) {
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
