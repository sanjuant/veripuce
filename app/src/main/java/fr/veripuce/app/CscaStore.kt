package fr.veripuce.app

import android.content.Context
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.io.encoding.Base64

/**
 * Magasin de certificats CSCA de confiance (Country Signing CA) — l'ancre de confiance de
 * la passive authentication (ICAO 9303 partie 11, étape 3).
 *
 * Les certificats sont chargés depuis `assets/csca/` (formats DER `.cer`/`.der`/`.crt` ou
 * PEM `.pem`, un fichier pouvant contenir plusieurs certificats). Voir le README.txt de ce
 * dossier pour la provenance du bundle embarqué et sa procédure de mise à jour.
 *
 * Le parsing passe par BouncyCastle : certaines CSCA anciennes utilisent des paramètres EC
 * explicites que les providers par défaut (SUN, Conscrypt) refusent. Chaque bloc PEM est
 * parsé individuellement — un certificat exotique est écarté sans sacrifier le reste.
 *
 * Sans certificat chargé, la signature du SOD reste vérifiée cryptographiquement mais
 * l'origine étatique ne peut pas être prouvée (VALID_UNTRUSTED).
 */
class CscaStore private constructor(val certificates: List<X509Certificate>) {

    val isEmpty: Boolean get() = certificates.isEmpty()

    companion object {
        private val EXTENSIONS = setOf("cer", "der", "crt", "pem")
        private val PEM_BLOCK =
            Regex("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\r\\n\\s]+)-----END CERTIFICATE-----")

        // Instance BC explicite : même comportement sur Android et en test JVM, sans
        // dépendre de l'enregistrement global du provider. Unique (un fichier par
        // certificat -> parse() est appelé des centaines de fois).
        private val certFactory: CertificateFactory by lazy {
            CertificateFactory.getInstance("X.509", BouncyCastleProvider())
        }

        fun load(context: Context): CscaStore {
            val certs = ArrayList<X509Certificate>()
            runCatching {
                for (name in context.assets.list("csca").orEmpty()) {
                    if (name.substringAfterLast('.').lowercase() !in EXTENSIONS) continue
                    runCatching {
                        context.assets.open("csca/$name").use { ins -> certs += parse(ins.readBytes()) }
                    }
                }
            }
            return CscaStore(certs)
        }

        /**
         * Parse un fichier de certificats : PEM multi-blocs (chaque bloc individuellement,
         * les illisibles sont ignorés) ou DER brut (un certificat).
         */
        fun parse(bytes: ByteArray): List<X509Certificate> {
            val cf = certFactory
            val text = bytes.toString(Charsets.ISO_8859_1)
            if ("-----BEGIN CERTIFICATE-----" !in text) {
                return listOfNotNull(
                    runCatching {
                        cf.generateCertificate(bytes.inputStream()) as X509Certificate
                    }.getOrNull(),
                )
            }
            return PEM_BLOCK.findAll(text).mapNotNull { block ->
                runCatching {
                    val der = Base64.Mime.decode(block.groupValues[1].trim())
                    cf.generateCertificate(der.inputStream()) as X509Certificate
                }.getOrNull()
            }.toList()
        }
    }
}
