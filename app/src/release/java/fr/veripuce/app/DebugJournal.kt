package fr.veripuce.app

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Build RELEASE : le journal des lectures n'existe pas. Ce bouchon vide remplace celui de
 * `src/debug` ; [ENABLED] constant à false -> les appels de MainActivity sont éliminés à la
 * compilation. Aucun fichier, aucun écran, aucun FileProvider (vérifié par verify.sh).
 */
@Suppress("UNUSED_PARAMETER", "unused")
object DebugJournal {
    const val ENABLED = false
    fun newTrace(): ReadTrace? = null
    fun onNewCard() {}
    fun onNewCheck() {}
    fun record(ctx: Context, trace: ReadTrace?, report: DebugReport?, attemptsFrom: Int, tapIndex: Int, secrets: List<String?>) {}
    fun install(activity: AppCompatActivity, anchor: View) {}
}
