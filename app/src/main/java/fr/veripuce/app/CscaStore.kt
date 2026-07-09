package fr.veripuce.app

import android.content.Context
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Magasin de certificats CSCA de confiance (Country Signing CA) — l'ancre de confiance de
 * la passive authentication (ICAO 9303 partie 11, étape 3).
 *
 * Les certificats sont chargés depuis `assets/csca/` (formats DER `.cer`/`.der`/`.crt` ou
 * PEM `.pem`, un fichier pouvant contenir plusieurs certificats). Sources à y déposer :
 * la masterlist ICAO PKD, ou pour la France les CSCA publiés par l'ANTS.
 *
 * Sans certificat chargé, la signature du SOD reste vérifiée cryptographiquement mais
 * l'origine étatique ne peut pas être prouvée (VALID_UNTRUSTED).
 */
class CscaStore private constructor(val certificates: List<X509Certificate>) {

    val isEmpty: Boolean get() = certificates.isEmpty()

    companion object {
        fun load(context: Context): CscaStore {
            val cf = CertificateFactory.getInstance("X.509")
            val certs = ArrayList<X509Certificate>()
            runCatching {
                val names = context.assets.list("csca").orEmpty()
                for (name in names) {
                    val lower = name.lowercase()
                    if (lower.endsWith(".cer") || lower.endsWith(".der") ||
                        lower.endsWith(".crt") || lower.endsWith(".pem")
                    ) {
                        runCatching {
                            context.assets.open("csca/$name").use { ins ->
                                // generateCertificates gère le DER (1 cert) comme le PEM (bundle).
                                cf.generateCertificates(ins).forEach { certs.add(it as X509Certificate) }
                            }
                        }
                    }
                }
            }
            return CscaStore(certs)
        }
    }
}
