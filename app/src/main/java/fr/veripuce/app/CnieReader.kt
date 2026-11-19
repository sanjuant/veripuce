package fr.veripuce.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.tech.IsoDep
import com.gemalto.jp2.JP2Decoder
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.Util
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.ImageInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate

/**
 * Lecture d'un document eMRTD (ICAO 9303) via NFC : CNIe française **ou** passeport.
 *
 * Flux : IsoDep -> CardService (SCUBA) -> PassportService (JMRTD) -> ouverture de session
 * (PACE-CAN pour la CNIe, PACE-MRZ / BAC pour le passeport) -> lecture des data groups
 * -> passive authentication (intégrité), cohérence MRZ optique/puce, détection de clone.
 */
/**
 * Étapes d'une lecture, émises au fil de l'eau pour l'indicateur de progression.
 * [percent] : progression approximative (la photo domine le temps de lecture).
 */
enum class ReadStep(val percent: Int) {
    CONNECT(8),    // ouverture de session sécurisée (PACE/BAC)
    IDENTITY(22),  // DG1 (état civil / MRZ)
    PHOTO(38),     // DG2 (photo, le plus long)
    SECURITY(78),  // DG13/14/15 + EF.SOD
    VERIFY(92),    // passive authentication + anti-clone
}

class CnieReader {

    companion object {
        /** Racine des OIDs PACE (BSI TR-03110) : .1/.2 = GM, .3/.4 = IM, .6 = CAM. */
        internal const val OID_PACE = "0.4.0.127.0.7.2.2.4"

        /** Timeout transceive pendant le handshake PACE (échec rapide sur un gel). */
        private const val HANDSHAKE_TIMEOUT_MS = 6_000
        /** Timeout transceive pendant la lecture des data groups (marge plus large). */
        private const val READ_TIMEOUT_MS = 15_000
        /**
         * Budget global du handshake d'un tap : on n'entame plus de nouveau mapping au-delà
         * (les mappings restants passent au tap suivant). 2 gels de 6 s tiennent dedans.
         */
        internal const val HANDSHAKE_BUDGET_MS = 13_000L

        /**
         * Rang de préférence d'un protocole PACE (plus petit = essayé en premier).
         * GM d'abord par défaut ; IM d'abord si [preferIm] (rotation après un gel de GM).
         */
        internal fun paceRank(oid: String, preferIm: Boolean): Int {
            val isGm = oid.startsWith("$OID_PACE.1.") || oid.startsWith("$OID_PACE.2.") // DH-GM / ECDH-GM
            val isIm = oid.startsWith("$OID_PACE.3.") || oid.startsWith("$OID_PACE.4.") // DH-IM / ECDH-IM
            val isCam = oid.startsWith("$OID_PACE.6.")                                  // ECDH-CAM
            return when {
                preferIm && isIm -> 0
                preferIm && isGm -> 1
                !preferIm && isGm -> 0
                !preferIm && isIm -> 1
                isCam -> 2
                else -> 3
            }
        }
    }

    /**
     * @param expectedMrz MRZ lue optiquement (scan), pour la comparer à la puce (DG1).
     *                    null si aucune comparaison n'est demandée.
     * @param onStep      callback de progression (appelé depuis le thread de lecture).
     * @param diag        collecteur de diagnostic OPTIONNEL (défaut null -> aucune collecte,
     *                    comportement de lecture strictement inchangé).
     * @param mappingState état de la rotation des mappings PACE pour la clé MRZ de la carte
     *                    courante, conservé d'un tap à l'autre par l'UI : les mappings qui ont
     *                    déjà échoué (gel GM…) ne sont plus retentés en MRZ, et passent en
     *                    dernier pour le CAN. null = aucune mémoire (tous les mappings, GM d'abord).
     * @param mrzCandidateOffset index de départ dans la liste des variantes du numéro de
     *                    document (paires aveugles). Sert à ITÉRER les candidats tap par tap :
     *                    une puce qui GÈLE (TagLost) sur une clé fausse ne permet pas d'essayer
     *                    la variante suivante dans la même session (tag perdu) — l'UI relance
     *                    donc avec l'offset suivant. 0 = numéro tel que lu (cas nominal).
     */
    fun read(
        isoDep: IsoDep,
        key: AccessKey,
        expectedMrz: MrzOcr.MrzData? = null,
        cscaCerts: Collection<X509Certificate> = emptyList(),
        onStep: ((ReadStep) -> Unit)? = null,
        diag: DiagnosticsCollector? = null,
        mappingState: MrzMappingState? = null,
        mrzCandidateOffset: Int = 0,
    ): ReadResult {
        // Délai COURT pour le handshake PACE : un échange normal répond en <2 s ; un gel
        // (ex. GM sur une carte qui couple mal) échoue ainsi en ~6 s au lieu de 15 s, ce
        // qui laisse la rotation IM tenter au tap suivant bien plus vite. La lecture des
        // data groups, elle, garde une marge plus large (restaurée après l'ouverture).
        isoDep.timeout = HANDSHAKE_TIMEOUT_MS
        diag?.apply {
            nfcExtendedLength = runCatching { isoDep.isExtendedLengthApduSupported }.getOrNull()
            nfcMaxTransceive = runCatching { isoDep.maxTransceiveLength }.getOrNull()
            nfcTimeoutMs = HANDSHAKE_TIMEOUT_MS
        }
        onStep?.invoke(ReadStep.CONNECT)

        val cardService = CardService.getInstance(isoDep)
        var service: PassportService? = null
        try {
            cardService.open()

            val svc = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                /* isSFIEnabled = */ false,
                // shouldCheckMAC=true : la détection de clone par Chip Authentication REPOSE
                // sur la vérification du MAC de l'échange sous le SM issu de la CA. Une vraie
                // puce produit toujours un MAC valide (aucune régression), un clone échoue.
                /* shouldCheckMAC = */ true,
            )
            service = svc
            svc.open()

            // 1) Ouverture de la session sécurisée selon le type de clé. openWith* ne lève
            //    ChipAccessException QUE sur un refus de clé avéré (SW 6300) : un aléa
            //    transitoire (perte de contact, glitch NFC) remonte tel quel -> l'UI invite
            //    à re-présenter la carte sans proposer le CAN.
            // Reconnexion après un gel : si la carte est toujours dans le champ, le tag
            // répond à nouveau et le mapping suivant est tenté dans le MÊME tap.
            val reconnect = { reconnectTag(isoDep, diag) }
            when (key) {
                is AccessKey.Can -> openWithCan(svc, key.can, diag, mappingState, reconnect)
                is AccessKey.Mrz -> openWithMrz(svc, key, expectedMrz, diag, mappingState, reconnect, mrzCandidateOffset)
            }

            // Session ouverte : marge plus large pour la lecture des data groups (DG2 photo).
            isoDep.timeout = READ_TIMEOUT_MS

            // 2) Lecture des data groups sur octets BRUTS on-card (cf. passive auth).
            onStep?.invoke(ReadStep.IDENTITY)
            val dg1Bytes = readEf(svc, PassportService.EF_DG1)!!
            onStep?.invoke(ReadStep.PHOTO)
            val dg2Bytes = readEf(svc, PassportService.EF_DG2)!!
            val dg1 = DG1File(dg1Bytes.inputStream())
            val dg2 = DG2File(dg2Bytes.inputStream())
            val mrz = dg1.mrzInfo

            val photo = extractPhoto(dg2)

            // DG13 (France), DG14 (Chip Authentication), DG15 (Active Authentication) :
            // optionnels selon le document. Lus en brut, inclus dans l'intégrité s'ils
            // existent (le SOD les référence).
            onStep?.invoke(ReadStep.SECURITY)
            val dg13Bytes = readEf(svc, PassportService.EF_DG13)
            val dg13 = dg13Bytes?.let { runCatching { Dg13Parser.parse(it) }.getOrNull() }
            val dg14Bytes = readEf(svc, PassportService.EF_DG14)
            val dg15Bytes = readEf(svc, PassportService.EF_DG15)
            val dg14 = dg14Bytes?.let { runCatching { DG14File(it.inputStream()) }.getOrNull() }
            val dg15 = dg15Bytes?.let { runCatching { DG15File(it.inputStream()) }.getOrNull() }

            diag?.dgPresent?.apply {
                clear(); add(1); add(2)
                if (dg13Bytes != null) add(13)
                if (dg14Bytes != null) add(14)
                if (dg15Bytes != null) add(15)
            }

            // 3) Passive authentication.
            //    (a) intégrité : hashs recalculés == hashs signés du SOD.
            val sodBytes = svc.getInputStream(PassportService.EF_SOD, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()
            onStep?.invoke(ReadStep.VERIFY)
            val sod = SODFile(sodBytes.inputStream())
            val integrityOk = PassiveAuth.verifyDataGroupHashes(
                sod,
                buildMap {
                    put(1, dg1Bytes)
                    put(2, dg2Bytes)
                    dg13Bytes?.let { put(13, it) }
                    dg14Bytes?.let { put(14, it) }
                    dg15Bytes?.let { put(15, it) }
                },
            )
            //    (b) authenticité : signature du SOD par le DSC, DSC -> CSCA de confiance.
            val signature = PassiveAuth.verifySodSignature(sodBytes, sod, cscaCerts)

            // 4) Cohérence MRZ imprimée (scan) <-> puce (DG1), quel que soit le mode d'accès.
            //    Accès CAN : la clé est indépendante de la MRZ, la comparaison est le seul
            //    contrôle. Accès MRZ : l'ouverture de session prouve déjà la correspondance
            //    (une mauvaise clé serait refusée) — on la vérifie quand même contre DG1 et
            //    on l'AFFICHE : le conseiller doit voir cette garantie aussi sur un passeport.
            //    null uniquement si aucune MRZ de référence n'a été fournie.
            val mrzMatchesScan: Boolean? = expectedMrz?.let { exp ->
                normDoc(mrz.documentNumber) == normDoc(exp.documentNumber) &&
                    mrz.dateOfBirth == exp.dateOfBirth &&
                    mrz.dateOfExpiry == exp.dateOfExpiry
            }

            // 5) Détection de clone (en dernier : Chip Authentication réétablit le SM).
            val (cloneCheck, cloneMethod) = detectClone(svc, dg14, dg15)

            return ReadResult(
                mrz = mrz.toString(),
                documentNumber = mrz.documentNumber,
                surname = mrz.primaryIdentifier,
                givenNames = mrz.secondaryIdentifier,
                dateOfBirth = mrz.dateOfBirth,
                dateOfExpiry = mrz.dateOfExpiry,
                nationality = mrz.nationality,
                photo = photo,
                dg13 = dg13,
                hashesMatchSod = integrityOk,
                signature = signature,
                mrzMatchesScan = mrzMatchesScan,
                cloneCheck = cloneCheck,
                cloneMethod = cloneMethod,
            )
        } finally {
            runCatching { service?.close() }
            runCatching { cardService.close() }
        }
    }

    /** Lit un EF en octets bruts, ou null s'il est absent/illisible (fichiers optionnels). */
    private fun readEf(svc: PassportService, fid: Short): ByteArray? =
        runCatching {
            svc.getInputStream(fid, PassportService.DEFAULT_MAX_BLOCKSIZE).readBytes()
        }.getOrNull()

    private fun extractPhoto(dg2: DG2File): Bitmap? {
        val imageInfo: ImageInfo? = dg2.subRecords.asSequence()
            .flatMap { block ->
                when (block) {
                    is FaceInfo -> block.faceImageInfos.asSequence()              // ISO 19794
                    is FaceImageDataBlock -> block.representationBlocks.asSequence() // ISO 39794
                    else -> emptySequence()
                }
            }
            .firstOrNull()
        return imageInfo?.let { info ->
            runCatching {
                val bytes = info.imageInputStream.readBytes()
                runCatching { JP2Decoder(bytes).decode() }.getOrNull()
                    ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }

    /**
     * Ferme puis rouvre la connexion ISO-DEP après un gel. Réussit seulement si la carte est
     * encore dans le champ ; sinon false (le tap suivant reprendra). Tracé dans le rapport.
     */
    private fun reconnectTag(isoDep: IsoDep, diag: DiagnosticsCollector?): Boolean {
        val start = System.nanoTime()
        val ok = runCatching {
            runCatching { isoDep.close() }
            isoDep.connect()
            isoDep.timeout = HANDSHAKE_TIMEOUT_MS
            isoDep.isConnected
        }.getOrDefault(false)
        diag?.recordAttempt("-", null, "reconnexion", if (ok) "OK" else "échec", elapsedMs(start))
        return ok
    }

    /** CNIe : PACE-CAN. Le CAN = les 6 chiffres imprimés au recto. */
    private fun openWithCan(
        service: PassportService,
        can: String,
        diag: DiagnosticsCollector?,
        mappingState: MrzMappingState?,
        reconnect: () -> Boolean,
    ) {
        require(can.length == 6 && can.all { it.isDigit() }) { "Le CAN doit faire 6 chiffres." }
        // Les mappings qui ont gelé en MRZ passent en dernier (pas exclus : le CAN peut y réussir).
        val paceInfos = readPaceInfos(service, diag, demoted = mappingState?.failedEver.orEmpty())
        val outcome = tryPace(service, PACEKeySpec.createCANKey(can), "CAN", paceInfos, diag, null, reconnect)
        when (outcome) {
            is PaceMappingRetry.Outcome.Success -> { service.sendSelectApplet(true); diag?.finalAccess = "CAN" }
            is PaceMappingRetry.Outcome.KeyRefused -> throw ChipAccessException(outcome.error)  // mauvais CAN
            is PaceMappingRetry.Outcome.ExplicitRefusal -> throw ChipAccessException(outcome.error)
            is PaceMappingRetry.Outcome.Interrupted -> throw outcome.error
            is PaceMappingRetry.Outcome.Exhausted -> throw outcome.error ?: IllegalStateException("PACE-CAN impossible")
        }
    }

    /**
     * Passeport / carte acceptant PACE-MRZ : clé dérivée de la MRZ.
     *
     * Pour une carte d'identité TD1, le numéro de document a pu être mal lu sur une paire
     * de glyphes aveugle au chiffre de contrôle (G/6, L/1…) — on retente donc PACE avec
     * des variantes du numéro avant de conclure au refus. BAC en dernier recours (jamais
     * pour une CNIe française, qui n'a pas de BAC).
     */
    private fun openWithMrz(
        service: PassportService,
        mrz: AccessKey.Mrz,
        expectedMrz: MrzOcr.MrzData?,
        diag: DiagnosticsCollector?,
        mappingState: MrzMappingState?,
        reconnect: () -> Boolean,
        mrzCandidateOffset: Int = 0,
    ) {
        val paceInfos = readPaceInfos(service, diag)
        // État émetteur si (et seulement si) le document est une carte d'identité TD1.
        val idCardState = expectedMrz?.takeIf { it.docType == MrzOcr.DocType.ID_CARD }?.issuingState
        val isIdCard = idCardState != null
        val isFrenchIdCard = idCardState.equals("FRA", ignoreCase = true)

        if (paceInfos.isEmpty()) {
            // Aucun PACE annoncé -> document ancien en BAC (jamais une CNIe FRA).
            service.sendSelectApplet(false)
            service.doBAC(BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry))
            diag?.finalAccess = "BAC"
            return
        }

        val candidates = if (isIdCard) {
            MrzKeyCandidates.documentNumberCandidates(mrz.documentNumber)
        } else {
            listOf(mrz.documentNumber)
        }

        // Itération tap par tap : on démarre au candidat [mrzCandidateOffset]. Sur une puce qui
        // REFUSE proprement (6300), la boucle ci-dessous essaie tous les candidats suivants dans
        // CE tap. Sur une puce qui GÈLE (TagLost), la rotation des mappings tente le mapping
        // suivant (même tap si le tag se reconnecte, sinon tap suivant, cf. [mappingState]) ;
        // l'UI ne propose le CAN qu'une fois TOUS les mappings annoncés en échec.
        val state = mappingState ?: MrzMappingState()
        var refusal: Throwable? = null
        for (docNumber in candidates.drop(mrzCandidateOffset.coerceIn(0, candidates.size))) {
            // Libellé masqué : n° original -> "MRZ" ; variante -> "MRZ-candidat(pos=X)".
            val label = candidateLabel(docNumber, mrz.documentNumber)
            val key = PACEKeySpec.createMRZKey(BACKey(docNumber, mrz.dateOfBirth, mrz.dateOfExpiry))
            when (val outcome = tryPace(service, key, label, paceInfos, diag, state, reconnect)) {
                is PaceMappingRetry.Outcome.Success -> {
                    service.sendSelectApplet(true)
                    diag?.finalAccess = if (docNumber == mrz.documentNumber) "MRZ" else "MRZ-candidat"
                    return
                }
                is PaceMappingRetry.Outcome.KeyRefused -> {
                    refusal = outcome.error
                    // 63Cx : la puce décompte des essais -> ne JAMAIS en provoquer un second.
                    if (PaceError.hasRetryCounter(outcome.error)) break
                    state.resetForNextKey()   // clé fausse -> candidat suivant, rotation complète
                }
                is PaceMappingRetry.Outcome.ExplicitRefusal -> {
                    // La puce refuse le mot de passe MRZ dès MSE:Set AT : les variantes du
                    // numéro n'y changeront rien. BAC éventuel ci-dessous, puis CAN.
                    refusal = outcome.error
                    break
                }
                // Gel sans reconnexion : le tap suivant reprendra aux mappings restants.
                is PaceMappingRetry.Outcome.Interrupted -> throw outcome.error
                // Tous les mappings en échec (non-6300) : les variantes n'y changeront rien ;
                // l'UI voit [MrzMappingState.allTried] et propose le CAN.
                is PaceMappingRetry.Outcome.Exhausted ->
                    throw outcome.error ?: IllegalStateException("PACE-MRZ impossible")
            }
        }

        // Tous les candidats MRZ refusés (6300). BAC en dernier recours, sauf CNIe FRA.
        if (!isFrenchIdCard) {
            val start = System.nanoTime()
            val bacError = runCatching {
                service.sendSelectApplet(false)
                service.doBAC(BACKey(mrz.documentNumber, mrz.dateOfBirth, mrz.dateOfExpiry))
                diag?.bacResult = "OK"
                diag?.finalAccess = "BAC"
                return
            }.exceptionOrNull()
            diag?.bacResult = bacError?.let { DebugReports.resultOf(it) }
        }
        throw ChipAccessException(refusal ?: IllegalStateException("PACE-MRZ refusé"))
    }

    /**
     * Libellé de tentative masqué : « MRZ » ou « MRZ-candidat(pos=X) ». On révèle la
     * POSITION retentée, jamais le caractère : sur une réussite par candidat, ce caractère
     * serait la valeur RÉELLE de la puce — canal de fuite à ne pas ouvrir.
     */
    private fun candidateLabel(docNumber: String, original: String): String {
        if (docNumber == original) return "MRZ"
        val i = docNumber.indices.firstOrNull { docNumber[it] != original.getOrNull(it) } ?: return "MRZ-candidat"
        return "MRZ-candidat(pos=$i)"
    }

    /**
     * Tente PACE sur les protocoles annoncés pour UNE clé, via [PaceMappingRetry] (rotation
     * des mappings, un essai par mapping, reconnexion après un gel, budget de temps).
     * [state] non null = clé MRZ (mémoire inter-taps, refus explicite à MSE:Set AT décisif).
     */
    private fun tryPace(
        service: PassportService,
        key: PACEKeySpec,
        keyLabel: String,
        paceInfos: List<PACEInfo>,
        diag: DiagnosticsCollector?,
        state: MrzMappingState?,
        reconnect: () -> Boolean,
    ): PaceMappingRetry.Outcome = PaceMappingRetry.run(
        infos = paceInfos,
        oidOf = { it.objectIdentifier },
        state = state,
        stopOnExplicitRefusal = state != null,
        budgetMs = HANDSHAKE_BUDGET_MS,
        attempt = { info ->
            service.doPACE(key, info.objectIdentifier, PACEInfo.toParameterSpec(info.parameterId), null)
        },
        reconnect = reconnect,
        onResult = { info, error, ms ->
            if (error == null) diag?.recordAttempt(keyLabel, info.objectIdentifier, "GA", "OK", ms)
            else diag?.recordFailure(keyLabel, info.objectIdentifier, error, ms)
        },
    )

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private fun readPaceInfos(
        service: PassportService,
        diag: DiagnosticsCollector?,
        demoted: Set<PaceMapping> = emptySet(),
    ): List<PACEInfo> {
        val cardAccess = runCatching {
            CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS, PassportService.DEFAULT_MAX_BLOCKSIZE))
        }.getOrNull() ?: return emptyList()
        // EF.CardAccess peut annoncer PLUSIEURS protocoles PACE, et JMRTD les stocke
        // dans un HashSet : « prendre le premier » est NON DÉTERMINISTE. On trie
        // explicitement : GM d'abord (Generic Mapping, le mieux éprouvé ; ReadID et
        // NFCPassportReader, GM-only, lisent la CNIe), puis IM, puis CAM. [demoted] (mappings
        // qui ont gelé en MRZ) passe en dernier — utilisé pour le CAN.
        val infos = cardAccess.securityInfos.filterIsInstance<PACEInfo>()
            .sortedWith(compareBy<PACEInfo>(
                { PaceMapping.of(it.objectIdentifier) in demoted },
                { paceRank(it.objectIdentifier, preferIm = false) },
            ))
        diag?.recordCardAccess(
            infos.map { DiagnosticsCollector.PACEInfoDescriptor(it.objectIdentifier, it.parameterId) }
        )
        return infos
    }

    /**
     * Détection de puce clonée. Une puce authentique détient une clé privée NON extractible
     * qu'un clone (copie des seuls data groups) ne peut pas reproduire.
     *
     * - **Chip Authentication** (DG14, EAC-CA) : mécanisme moderne. On établit le canal CA
     *   puis on force un échange sous le nouveau Secure Messaging : son MAC n'est valide que
     *   si la puce a dérivé la même clé — donc que si elle détient la clé privée.
     * - **Active Authentication** (DG15) : repli. La puce signe un défi aléatoire. Vérifié de
     *   façon fiable ici pour les clés EC (ECDSA) ; conservateur sinon (jamais de fausse
     *   accusation : on ne conclut « authentique » que sur preuve, sinon « non vérifié »).
     */
    private fun detectClone(svc: PassportService, dg14: DG14File?, dg15: DG15File?): Pair<CloneCheck, String?> {
        // securityInfos (non déprécié) plutôt que les getters chipAuthentication* dépréciés.
        val secInfos = dg14?.securityInfos.orEmpty()
        secInfos.filterIsInstance<ChipAuthenticationPublicKeyInfo>().firstOrNull()?.let { capki ->
            val pubKey = capki.subjectPublicKey
            val caInfos = secInfos.filterIsInstance<ChipAuthenticationInfo>()
            val caInfo = caInfos.firstOrNull { it.keyId == capki.keyId } ?: caInfos.firstOrNull()
            // (a) Établissement du canal CA. Un échec ici est INCONCLUSIF (algorithme/courbe
            //     non supportés, aléa NFC…) — jamais une preuve de clone.
            val established = runCatching {
                val oid = caInfo?.objectIdentifier ?: Util.inferProtocolIdentifier(pubKey)
                svc.doEACCA(capki.keyId, oid, Util.inferKeyAgreementAlgorithm(pubKey), pubKey)
                true
            }.getOrDefault(false)
            if (!established) return CloneCheck.UNSUPPORTED to "Chip Authentication (non établie)"
            // (b) Confirmation : lecture sous le SM issu de la CA. Avec shouldCheckMAC=true, le
            //     MAC n'est valide que si la puce détient la clé privée -> un échec ici = clone.
            val confirmed = runCatching {
                svc.getInputStream(PassportService.EF_DG1, PassportService.DEFAULT_MAX_BLOCKSIZE).read()
                true
            }.getOrDefault(false)
            return (if (confirmed) CloneCheck.AUTHENTIC else CloneCheck.FAILED) to "Chip Authentication"
        }

        dg15?.publicKey?.let { aaKey ->
            val authentic = runCatching {
                if (!aaKey.algorithm.uppercase().contains("EC")) return@runCatching false
                val challenge = ByteArray(8).also { SecureRandom().nextBytes(it) }
                // La réponse (signature) ne dépend pas du digest passé à doAA : on l'obtient
                // une fois, puis on tente plusieurs profils de vérification (le document peut
                // imposer SHA-1/256/384/512) pour ne pas conclure à tort à un non-support.
                val aa = svc.doAA(aaKey, "SHA-256", "SHA256withPLAIN-ECDSA", challenge)
                sequenceOf("SHA256withPLAIN-ECDSA", "SHA1withPLAIN-ECDSA", "SHA384withPLAIN-ECDSA", "SHA512withPLAIN-ECDSA")
                    .any { alg ->
                        runCatching {
                            val sig = Signature.getInstance(alg, "BC")
                            sig.initVerify(aaKey); sig.update(challenge); sig.verify(aa.response)
                        }.getOrDefault(false)
                    }
            }.getOrDefault(false)
            // Conservateur : AA réussie = authentique ; sinon "non conclu" (jamais FAILED,
            // pour ne pas accuser à tort un vrai document sur un profil AA non couvert).
            return (if (authentic) CloneCheck.AUTHENTIC else CloneCheck.UNSUPPORTED) to "Active Authentication"
        }

        return CloneCheck.UNSUPPORTED to null
    }

    /** Normalise un n° de document pour comparaison (majuscules, sans '<' ni séparateurs). */
    private fun normDoc(s: String): String = s.uppercase().filter { it.isLetterOrDigit() }
}
