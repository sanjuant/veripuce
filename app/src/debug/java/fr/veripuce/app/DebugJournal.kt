package fr.veripuce.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import fr.veripuce.app.journal.ApduTracer
import fr.veripuce.app.journal.JournalRedaction
import fr.veripuce.app.journal.JournalStore
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Journal des lectures — BUILD DEBUG UNIQUEMENT (`src/debug`). Le build release compile à la
 * place un `DebugJournal` vide (`src/release`) : ni fichier, ni écran, ni FileProvider.
 *
 * Chaque tentative de lecture (succès ou échec, MRZ ou CAN) ajoute une entrée CAVIARDÉE
 * ([JournalRedaction]) au fichier privé `files/journal/lectures.txt` ; la section « Journal des
 * lectures » de l'écran principal permet de tout partager en un fichier, ou de vider.
 */
object DebugJournal {

    const val ENABLED = true

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var section: WeakReference<Section>? = null
    private var cardIndex = 0

    private fun store(ctx: Context) = JournalStore(File(ctx.filesDir, "journal/lectures.txt"))

    /** Trace APDU pour la lecture qui commence. */
    fun newTrace(): ReadTrace = ApduTracer()

    /** Nouveau document scanné : numéro de carte suivant (dans la session). */
    fun onNewCard() { cardIndex++ }

    /** « Nouvelle vérification » : l'étiquette de la carte précédente ne doit pas déborder. */
    fun onNewCheck() { section?.get()?.labelInput?.text = null }

    /**
     * Ajoute une entrée pour la lecture qui vient de finir. [secrets] = valeurs brutes connues
     * (numéro, dates, CAN, noms, MRZ) : elles ne servent qu'à être effacées du texte.
     */
    fun record(
        ctx: Context,
        trace: ReadTrace?,
        report: DebugReport?,
        attemptsFrom: Int,
        tapIndex: Int,
        secrets: List<String?>,
    ) {
        if (report == null) return
        val text = JournalRedaction.render(
            JournalRedaction.Input(
                label = section?.get()?.labelInput?.text?.toString(),
                cardIndex = cardIndex,
                tapIndex = tapIndex,
                buildType = BuildConfig.BUILD_TYPE,
                report = report,
                attemptsFrom = attemptsFrom,
                trace = (trace as? ApduTracer)?.snapshot().orEmpty(),
                secrets = secrets,
            )
        )
        val app = ctx.applicationContext
        io.execute {
            runCatching { store(app).append(text) }
            refreshCount(app)
        }
    }

    /** Ajoute la section « Journal des lectures » juste après [anchor] (même parent). */
    fun install(activity: AppCompatActivity, anchor: View) {
        val parent = anchor.parent as? ViewGroup ?: return
        val view = LayoutInflater.from(activity).inflate(R.layout.journal_section, parent, false)
        parent.addView(view, parent.indexOfChild(anchor) + 1)
        val s = Section(
            count = view.findViewById(R.id.journalCount),
            labelInput = view.findViewById(R.id.journalLabel),
        )
        section = WeakReference(s)
        view.findViewById<MaterialButton>(R.id.journalShare).setOnClickListener { share(activity) }
        view.findViewById<MaterialButton>(R.id.journalClear).setOnClickListener { confirmClear(activity) }
        val app = activity.applicationContext
        io.execute { refreshCount(app) }
    }

    private class Section(val count: TextView, val labelInput: TextInputEditText)

    private fun refreshCount(app: Context) {
        val n = runCatching { store(app).count() }.getOrDefault(0)
        main.post {
            section?.get()?.count?.text =
                app.resources.getQuantityString(R.plurals.journal_count, n, n, JournalStore.MAX_ENTRIES)
        }
    }

    private fun share(activity: AppCompatActivity) {
        val app = activity.applicationContext
        io.execute {
            val text = runCatching { store(app).export() }.getOrNull()
            val out = text?.let {
                // Copie horodatée dans le cache (nom de fichier lisible chez le destinataire).
                val dir = File(app.cacheDir, "journal").apply { deleteRecursively(); mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                File(dir, "veripuce-journal-$stamp.txt").apply { writeText(it) }
            }
            main.post {
                if (out == null) {
                    Toast.makeText(activity, R.string.journal_empty, Toast.LENGTH_SHORT).show()
                    return@post
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.journal", out)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_SUBJECT, out.name)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                send.clipData = ClipData.newRawUri(out.name, uri)
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.journal_share)))
            }
        }
    }

    private fun confirmClear(activity: AppCompatActivity) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.journal_clear_title)
            .setMessage(R.string.journal_clear_text)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.journal_clear) { _, _ ->
                val app = activity.applicationContext
                io.execute {
                    store(app).clear()
                    File(app.cacheDir, "journal").deleteRecursively()
                    refreshCount(app)
                }
            }
            .show()
    }
}
