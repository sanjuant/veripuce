package fr.veripuce.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Moteur OCR basé sur Tesseract 5 (LSTM) piloté par un modèle OCR-B/MRZ dédié
 * ([assets/tessdata/mrz.traineddata], BSD-3, DoubangoTelecom). Contrairement au recognizer
 * latin générique de ML Kit, ce modèle est entraîné sur la police OCR-B des MRZ : il ne
 * confond plus G/6, S/8, C/2… ni ne produit les gros ratés (« FRA » -> « LEF », numéro tout
 * en lettres) qui forçaient le repli CAN sur certaines cartes.
 *
 * 100 % hors-ligne : l'AAR Tesseract4Android ne déclare AUCUNE permission (rien à retirer),
 * et l'inférence est native/on-device — aucune donnée ne quitte l'appareil.
 *
 * Non thread-safe (une instance par thread). Ici l'instance vit sur le thread d'analyse
 * caméra mono-thread de [ScanActivity]. L'init JNI est coûteux -> une seule fois, pas par image.
 */
class TesseractOcrEngine private constructor(private val api: TessBaseAPI) : OcrEngine {

    // recognize() tourne sur le thread d'analyse caméra, close() est appelé sur le thread UI
    // (onDestroy). TessBaseAPI est natif et NON thread-safe : recycler pendant une
    // reconnaissance planterait le natif (SIGSEGV). Ce verrou les rend mutuellement exclusifs,
    // et le drapeau [closed] neutralise toute reconnaissance postérieure à la libération.
    private val lock = Any()
    private var closed = false

    override fun recognize(bitmap: Bitmap): String? {
        synchronized(lock) {
            if (closed) return null
            return try {
                api.setImage(bitmap)
                val text = api.getUTF8Text()
                api.clear()                   // libère l'image native après chaque trame
                text?.takeIf { it.isNotBlank() }
            } catch (t: Throwable) {
                Log.w(TAG, "reconnaissance Tesseract en échec", t)
                null
            }
        }
    }

    override fun close() {
        synchronized(lock) {                  // attend une reconnaissance en cours
            if (closed) return                // idempotent : jamais deux recycle()
            closed = true
            runCatching { api.recycle() }
        }
    }

    companion object {
        private const val TAG = "TesseractOcr"
        private const val LANG = "mrz"                       // = mrz.traineddata
        private const val MODEL_BYTES = 1_452_847L           // taille exacte du modèle embarqué
        // Alphabet MRZ ICAO 9303 : 0-9, A-Z, remplissage '<'. Restreint les sorties possibles
        // (en plus de l'unicharset du modèle) et écarte minuscules/ponctuation parasites.
        private const val WHITELIST = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<"

        /**
         * Installe le modèle et initialise Tesseract. Renvoie `null` en cas d'échec
         * (l'appelant retombe alors proprement sur la saisie manuelle). Opération I/O + JNI :
         * à appeler hors du thread UI.
         */
        fun create(context: Context): TesseractOcrEngine? {
            val dataPath = runCatching { installTessData(context) }.getOrElse {
                Log.e(TAG, "copie du modèle MRZ impossible", it)
                return null
            }
            val api = TessBaseAPI()
            val ok = runCatching {
                api.init(dataPath, LANG, TessBaseAPI.OEM_LSTM_ONLY)
            }.getOrDefault(false)
            if (!ok) {
                Log.e(TAG, "init Tesseract (modèle '$LANG') en échec")
                runCatching { api.recycle() }
                return null
            }
            // Bande MRZ = bloc uniforme de 2-3 lignes -> laisser Tesseract les segmenter.
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
            api.setVariable("tessedit_char_whitelist", WHITELIST)
            // Les MRZ ne sont pas des mots de dictionnaire : couper les DAWG évite des
            // « corrections » erronées vers du vocabulaire.
            api.setVariable("load_system_dawg", "0")
            api.setVariable("load_freq_dawg", "0")
            return TesseractOcrEngine(api)
        }

        /**
         * Copie une fois `assets/tessdata/mrz.traineddata` vers `filesDir/tessdata/`
         * (Tesseract exige un chemin fichier réel, pas un asset). Renvoie le `datapath` à
         * passer à [TessBaseAPI.init] (le dossier PARENT de `tessdata/`).
         */
        private fun installTessData(context: Context): String {
            val tessDir = File(context.filesDir, "tessdata")
            if (!tessDir.exists()) tessDir.mkdirs()
            val model = File(tessDir, "$LANG.traineddata")
            val asset = "tessdata/$LANG.traineddata"
            // Recopie si absent ou taille différente de la version embarquée (mise à jour du
            // modèle). On compare à une taille figée : available() n'est pas fiable sur un
            // asset potentiellement compressé.
            if (!model.exists() || model.length() != MODEL_BYTES) {
                context.assets.open(asset).use { input ->
                    model.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return context.filesDir.absolutePath
        }
    }
}
