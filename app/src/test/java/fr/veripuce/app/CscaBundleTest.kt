package fr.veripuce.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate

/**
 * Garde-fou sur le magasin CSCA embarqué (`assets/csca/`, un fichier PEM par certificat),
 * via la logique de production [CscaStore.parse] :
 *  - le magasin doit fournir des certificats exploitables (certaines CSCA utilisent des
 *    paramètres EC explicites que seul BouncyCastle sait lire) ;
 *  - les CSCA françaises doivent être présentes — sans elles, aucun document français
 *    ne peut être « émis par l'État » ;
 *  - intégrité d'audit : 1 fichier = 1 certificat, l'empreinte SHA-256 du nom de fichier
 *    correspond au contenu, et le MANIFEST.tsv recense exactement les fichiers présents.
 */
class CscaBundleTest {

    private fun cscaDir(): File {
        // Les tests unitaires s'exécutent avec app/ comme répertoire de travail.
        val fromApp = File("src/main/assets/csca")
        return if (fromApp.isDirectory) fromApp else File("app/src/main/assets/csca")
    }

    private fun pemFiles(): List<File> =
        cscaDir().listFiles().orEmpty().filter { it.extension.lowercase() == "pem" }

    private fun loadAll(): List<X509Certificate> = pemFiles().flatMap { CscaStore.parse(it.readBytes()) }

    @Test
    fun `le magasin CSCA se charge avec une perte quasi nulle`() {
        val certs = loadAll()
        // Le magasin déposé contient ~770 certificats ; si le parsing en perd plus de
        // quelques-uns, un provider ou une regex a régressé.
        assertTrue("Seulement ${certs.size} certificats CSCA chargés (>= 700 attendus)", certs.size >= 700)
    }

    @Test
    fun `le magasin contient les CSCA francaises passeport et eID`() {
        val frNames = loadAll()
            .map { it.subjectX500Principal.name }
            .filter { it.contains("C=FR") }
        assertTrue("Aucune CSCA-FRANCE dans le magasin", frNames.any { it.contains("CSCA-FRANCE") })
        assertTrue("Pas de CSCA eID-FRANCE (CNIe) dans le magasin", frNames.any { it.contains("eID-FRANCE") })
    }

    @Test
    fun `les CSCA francaises sont des CA (basicConstraints)`() {
        // Limité aux FR : de vieilles CSCA étrangères peuvent omettre l'extension
        // basicConstraints, mais les CSCA françaises la portent toujours (CA:TRUE).
        val fr = loadAll().filter { it.subjectX500Principal.name.contains("C=FR") }
        val nonCa = fr.filter { it.basicConstraints == -1 }
        assertTrue(
            "CSCA françaises non-CA : ${nonCa.map { it.subjectX500Principal.name }}",
            nonCa.isEmpty(),
        )
    }

    @Test
    fun `audit - un fichier par certificat et empreinte du nom conforme au contenu`() {
        val sha256 = MessageDigest.getInstance("SHA-256")
        for (f in pemFiles()) {
            val certs = CscaStore.parse(f.readBytes())
            assertEquals("${f.name} : 1 certificat attendu", 1, certs.size)
            val actual = sha256.digest(certs[0].encoded).joinToString("") { "%02x".format(it) }
            val claimed = f.nameWithoutExtension.substringAfterLast('_')
            assertTrue(
                "${f.name} : l'empreinte du nom ($claimed) ne correspond pas au contenu ($actual)",
                actual.startsWith(claimed.lowercase()),
            )
        }
    }

    @Test
    fun `audit - le MANIFEST recense exactement les fichiers presents`() {
        val manifest = File(cscaDir(), "MANIFEST.tsv")
        assertTrue("MANIFEST.tsv manquant", manifest.isFile)
        val rows = manifest.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("fichier\t") }
            .map { it.substringBefore('\t') }
        assertEquals("Lignes du manifest != fichiers .pem", pemFiles().size, rows.size)
        val onDisk = pemFiles().map { it.name }.toSet()
        val missing = rows.filterNot { it in onDisk }
        assertTrue("Fichiers du manifest absents du dossier : $missing", missing.isEmpty())
    }
}
