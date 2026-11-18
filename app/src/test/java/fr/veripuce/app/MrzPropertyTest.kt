package fr.veripuce.app

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.ascii
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests de PROPRIÉTÉS du parsing MRZ (ICAO 9303) et du lecteur BER-TLV.
 *
 * Au lieu de quelques exemples, on génère des centaines de documents FICTIFS (champs tirés au
 * hasard, aucune vraie pièce) et on vérifie des lois :
 *  - aller-retour : une MRZ formatée selon ICAO 9303 (chiffres de contrôle compris) est relue
 *    à l'identique, quelle que soit sa fragmentation OCR ;
 *  - chiffres de contrôle : toute altération d'un chiffre ou d'un chiffre de contrôle est rejetée ;
 *  - entrées invalides : texte ou octets arbitraires -> null / IllegalArgumentException, jamais
 *    un autre crash.
 *
 * Graine FIXE par propriété : exécution déterministe (pas de test flaky). Un contre-exemple
 * trouvé est réduit (shrinking) et affiché avec sa graine par Kotest.
 */
class MrzPropertyTest {

    // ------------------------------------------------------------------ générateurs

    private val letters = ('A'..'Z').toList()
    private val digits = ('0'..'9').toList()
    private val alnum = letters + digits

    private fun arbText(alphabet: List<Char>, sizes: IntRange): Arb<String> =
        Arb.list(Arb.element(alphabet), sizes).map { it.joinToString("") }

    /** N° de document : 1 à 9 caractères alphanumériques, complétés par '<' (ICAO). */
    private val arbDocNumber = arbText(alnum, 1..9)

    /** Date AAMMJJ dans la plage que le parseur juge plausible (mois 1-12, jour 1-31). */
    private val arbDate: Arb<String> = Arb.bind(Arb.int(0..99), Arb.int(1..12), Arb.int(1..31)) { y, m, d ->
        "%02d%02d%02d".format(y, m, d)
    }

    private val arbSex = Arb.element('M', 'F', '<')

    /** Code pays 1 à 3 lettres, complété par '<' (ex. « D<< »). */
    private val arbState = arbText(letters, 1..3)

    /** Spécimen fictif généré (jamais une vraie personne). */
    private data class Specimen(
        val docNumber: String,
        val dob: String,
        val exp: String,
        val sex: Char,
        val state: String,
    )

    private val arbSpecimen: Arb<Specimen> =
        Arb.bind(arbDocNumber, arbDate, arbDate, arbSex, arbState) { doc, dob, exp, sex, st ->
            Specimen(doc, dob, exp, sex, st)
        }

    private fun ck(field: String): Char = '0' + MrzKeyCandidates.icaoCheckDigit(field)

    // ------------------------------------------------------------------ formateurs ICAO 9303

    /** Ligne 2 d'un TD3 (passeport), 44 caractères, chiffres de contrôle et composite compris. */
    private fun td3Line2(s: Specimen): String {
        val doc = s.docNumber.padEnd(9, '<')
        val opt = "<".repeat(14)
        val core = doc + ck(doc)
        val dob = s.dob + ck(s.dob)
        val exp = s.exp + ck(s.exp)
        val optCk = opt + '<'
        val composite = ck(core + dob + exp + optCk)
        val line = core + s.state.padEnd(3, '<') + dob + s.sex + exp + optCk + composite
        check(line.length == 44) { "TD3 ligne 2 de ${line.length} car." }
        return line
    }

    private fun td3(s: Specimen): String {
        val line1 = "P<${s.state.padEnd(3, '<')}SPECIMEN<<TEST".padEnd(44, '<')
        return line1 + "\n" + td3Line2(s)
    }

    /** TD1 (carte / titre de séjour) : 3 lignes de 30 caractères. */
    private fun td1(s: Specimen, docCode: String): String {
        val doc = s.docNumber.padEnd(9, '<')
        val line1 = (docCode + s.state.padEnd(3, '<') + doc + ck(doc)).padEnd(30, '<')
        val upper = s.dob + ck(s.dob) + s.sex + s.exp + ck(s.exp) + s.state.padEnd(3, '<') + "<".repeat(11)
        val composite = ck(line1.substring(5, 30) + upper.substring(0, 7) + upper.substring(8, 15) + upper.substring(18, 29))
        val line2 = upper + composite
        val line3 = "SPECIMEN<<TEST".padEnd(30, '<')
        check(line1.length == 30 && line2.length == 30) { "TD1 mal formé" }
        return "$line1\n$line2\n$line3"
    }

    private fun expectedTd1Type(docCode: String): MrzOcr.DocType = when {
        docCode[0] != 'I' -> MrzOcr.DocType.RESIDENCE_PERMIT
        docCode[1] == 'R' -> MrzOcr.DocType.RESIDENCE_PERMIT
        else -> MrzOcr.DocType.ID_CARD
    }

    private fun expectedTd3(s: Specimen) =
        MrzOcr.MrzData(s.docNumber, s.dob, s.exp, MrzOcr.DocType.PASSPORT, s.state)

    private fun prop(seed: Long, iterations: Int = 500) = PropTestConfig(seed = seed, iterations = iterations)

    /** JUnit 4 exige des méthodes `void` : on enveloppe les checkAll (suspend) ici. */
    private fun property(block: suspend () -> Unit) {
        runBlocking { block() }
    }

    // ------------------------------------------------------------------ aller-retour

    @Test
    fun `TD3 - formatage puis parsing rend les memes champs`() = property {
        checkAll(prop(9303_01), arbSpecimen) { s ->
            assertEquals(expectedTd3(s), MrzOcr.findMrz(td3(s)), td3(s))
        }
    }

    @Test
    fun `TD1 - formatage puis parsing rend les memes champs et le bon type`() = property {
        val codes = Arb.element("ID", "IR", "I<", "IP", "AC", "AR", "C<", "CR")
        checkAll(prop(9303_02), arbSpecimen, codes) { s, code ->
            val text = td1(s, code)
            val expected = MrzOcr.MrzData(s.docNumber, s.dob, s.exp, expectedTd1Type(code), s.state)
            assertEquals(expected, MrzOcr.findMrz(text), text)
        }
    }

    @Test
    fun `fragmentation OCR, minuscules et guillemets ne changent pas la lecture`() = property {
        // ML Kit / Tesseract coupent les lignes, insèrent des espaces, lisent « pour <.
        val noise = Arb.list(Arb.element(' ', '\n', '\t', '.', ','), 0..12)
        val positions = Arb.list(Arb.int(0..200), 0..12)
        checkAll(prop(9303_03), arbSpecimen, noise, positions, Arb.element(true, false)) { s, seps, pos, td1 ->
            val clean = if (td1) td1(s, "ID") else td3(s)
            val sb = StringBuilder(clean.replace('<', '«').lowercase())
            seps.zip(pos).forEach { (c, p) -> sb.insert(p % (sb.length + 1), c) }
            assertEquals(MrzOcr.findMrz(clean), MrzOcr.findMrz(sb.toString()), sb.toString())
        }
    }

    @Test
    fun `confusions OCR lettre-chiffre en champ numerique sont corrigees`() = property {
        // 0->O/Q, 1->I, 2->Z, 5->S, 8->B : réparés par fixDigits avant la validation.
        val confusion = mapOf('0' to "OQ", '1' to "I", '2' to "Z", '5' to "S", '8' to "B")
        checkAll(prop(9303_04), arbSpecimen, Arb.list(Arb.int(0..15), 0..15), Arb.int(0..1)) { s, picks, alt ->
            val line = td3Line2(s).toCharArray()
            // Positions numériques : ck doc (9), ddn+ck (13..19), exp+ck (21..27).
            val numeric = listOf(9) + (13..19) + (21..27)
            for (p in picks) {
                val i = numeric[p % numeric.size]
                val repl = confusion[line[i]] ?: continue
                line[i] = repl[alt % repl.length]
            }
            assertEquals(expectedTd3(s), MrzOcr.findMrz(String(line)), String(line))
        }
    }

    // ------------------------------------------------------------------ chiffres de contrôle

    @Test
    fun `tout chiffre de controle faux est rejete`() = property {
        // Positions des chiffres de contrôle de la ligne 2 TD3 : doc 9, ddn 19, exp 27.
        checkAll(prop(9303_05), arbSpecimen, Arb.element(9, 19, 27), Arb.int(1..9)) { s, pos, delta ->
            val line = td3Line2(s).toCharArray()
            line[pos] = '0' + ((line[pos] - '0' + delta) % 10)
            assertNull(MrzOcr.findMrz(String(line)), String(line))
        }
    }

    @Test
    fun `alterer un chiffre d'une date est detecte par le chiffre de controle`() = property {
        // Pondération 7-3-1 : chaque poids est premier avec 10 -> toute substitution
        // chiffre->chiffre change le chiffre de contrôle.
        checkAll(prop(9303_06), arbSpecimen, Arb.int(0..11), Arb.int(1..9)) { s, k, delta ->
            val line = td3Line2(s).toCharArray()
            val i = if (k < 6) 13 + k else 21 + (k - 6)   // un chiffre de ddn ou d'exp
            line[i] = '0' + ((line[i] - '0' + delta) % 10)
            assertNull(MrzOcr.findMrz(String(line)), String(line))
        }
    }

    @Test
    fun `icaoCheckDigit - toute substitution chiffre par chiffre change le controle`() = property {
        checkAll(prop(9303_07, 1000), arbText(alnum + '<', 1..30), Arb.int(0..29), Arb.int(1..9)) { f, k, delta ->
            val i = k % f.length
            if (f[i] !in '0'..'9') return@checkAll
            val mutated = f.substring(0, i) + ('0' + ((f[i] - '0' + delta) % 10)) + f.substring(i + 1)
            assertNotEquals(MrzKeyCandidates.icaoCheckDigit(f), MrzKeyCandidates.icaoCheckDigit(mutated), "$f -> $mutated")
        }
    }

    @Test
    fun `candidats sur paires aveugles - meme controle ICAO, original en tete, sans doublon`() = property {
        val blindRich = arbText(listOf('G', '6', 'Q', 'L', '1', 'S', '8', 'C', '2', 'D', '3', 'X', '4', '<'), 1..9)
        checkAll(prop(9303_08), blindRich) { doc ->
            val cands = MrzKeyCandidates.documentNumberCandidates(doc)
            assertEquals(doc, cands.first())
            assertEquals(cands.size, cands.toSet().size)
            assertTrue(cands.size <= 16)
            for (c in cands.drop(1)) {
                assertEquals(MrzKeyCandidates.icaoCheckDigit(doc), MrzKeyCandidates.icaoCheckDigit(c), "$doc -> $c")
                assertTrue(MrzKeyCandidates.differOnlyByBlindPairs(doc, c), "$doc -> $c")
            }
        }
    }

    // ------------------------------------------------------------------ entrées invalides

    @Test
    fun `MRZ tronquee avant le dernier chiffre de controle - aucune lecture`() = property {
        checkAll(prop(9303_09, 200), arbSpecimen) { s ->
            val line = td3Line2(s)
            for (n in 0 until 28) assertNull(MrzOcr.findMrz(line.take(n)), line.take(n))
        }
    }

    @Test
    fun `texte arbitraire - jamais de crash, toute lecture respecte les invariants`() = property {
        // Deux sources : ASCII quelconque, et alphabet MRZ dense (plus proche des motifs).
        val mrzish = arbText(alnum + listOf('<', '<', '<', '\n', ' '), 0..250)
        val ascii = Arb.string(0..250, Codepoint.ascii())
        for ((seed, gen) in listOf(9303_10L to mrzish, 9303_11L to ascii)) {
            checkAll(prop(seed, 1000), gen) { text ->
                val mrz = MrzOcr.findMrz(text)
                MrzOcr.findCan(text)?.let { assertTrue(it.length == 6 && it.all(Char::isDigit), it) }
                MrzOcr.diagnose(text)
                if (mrz != null) {
                    assertTrue(Regex("[A-Z0-9<]{0,9}").matches(mrz.documentNumber), mrz.toString())
                    assertTrue(!mrz.documentNumber.endsWith('<'), mrz.toString())
                    for (d in listOf(mrz.dateOfBirth, mrz.dateOfExpiry)) {
                        assertTrue(d.length == 6 && d.all(Char::isDigit), mrz.toString())
                        assertTrue(d.substring(2, 4).toInt() in 1..12 && d.substring(4, 6).toInt() in 1..31, mrz.toString())
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ BER-TLV (LDS ICAO 9303-10)

    private data class Node(val tag: Int, val tagBytes: ByteArray, val value: ByteArray)

    /** Tag d'1 octet (hors forme longue 0x1F et hors 0x00/0xFF, pris pour du padding) ou de 2 octets (0x5F1F…, 0x7F21…). */
    private val arbTag: Arb<Pair<Int, ByteArray>> = Arb.bind(Arb.int(0..1), Arb.int(1..0xFE), Arb.int(0..0x7F)) { kind, b1, b2 ->
        if (kind == 0 && b1 and 0x1F != 0x1F) {
            b1 to byteArrayOf(b1.toByte())
        } else {
            // Bits de classe/construit sur 3 bits, sauf 0b111 (0xFF : octet invalide ISO 7816,
            // pris pour du padding).
            val first = (((b1 shr 5) % 7) shl 5) or 0x1F
            ((first shl 8) or b2) to byteArrayOf(first.toByte(), b2.toByte())
        }
    }

    private val arbNode: Arb<Node> =
        Arb.bind(arbTag, Arb.byteArray(Arb.int(0..300), Arb.byte())) { (tag, tb), v -> Node(tag, tb, v) }

    /** Longueur BER : forme courte < 0x80, sinon forme longue minimale (0x81 / 0x82). */
    private fun berLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n <= 0xFF -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun encode(nodes: List<Node>): ByteArray =
        nodes.fold(ByteArray(0)) { acc, n -> acc + n.tagBytes + berLen(n.value.size) + n.value }

    @Test
    fun `BER-TLV - encodage puis parsing rend les memes noeuds, padding final tolere`() = property {
        checkAll(prop(9303_12), Arb.list(arbNode, 0..6), Arb.int(0..8), Arb.element(0x00, 0xFF)) { nodes, pad, padByte ->
            val bytes = encode(nodes)
            val padded = bytes + ByteArray(pad) { padByte.toByte() }
            for (parsed in listOf(BerTlv.parse(bytes, allowPadding = false), BerTlv.parse(padded))) {
                assertEquals(nodes.map { it.tag }, parsed.map { it.tag })
                nodes.zip(parsed).forEach { (n, p) -> assertTrue(n.value.contentEquals(p.value)) }
            }
        }
    }

    @Test
    fun `BER-TLV et DG13 - octets arbitraires rejetes proprement`() = property {
        // Seule IllegalArgumentException (require) est admise : pas d'index hors bornes, pas
        // de boucle infinie, pas d'OutOfMemory sur une longueur déclarée géante.
        checkAll(prop(9303_13, 2000), Arb.byteArray(Arb.int(0..64), Arb.byte())) { bytes ->
            for (parse in listOf<(ByteArray) -> Any>({ BerTlv.parse(it) }, { BerTlv.parseTagList(it) }, { Dg13Parser.parse(it) })) {
                try {
                    parse(bytes)
                } catch (_: IllegalArgumentException) {
                    // rejet attendu d'une entrée malformée
                } catch (t: Throwable) {
                    fail("${t::class.simpleName} sur ${bytes.joinToString(" ") { "%02x".format(it) }} : ${t.message}")
                }
            }
        }
    }
}
