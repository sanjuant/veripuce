package fr.veripuce.app

import android.nfc.TagLostException
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.CardServiceProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotation des mappings PACE (GM -> IM -> CAM) sur une « fausse puce » JVM : aucune donnée
 * d'identité, seulement des OIDs publics (BSI TR-03110) et des status words.
 */
class PaceMappingRetryTest {

    private val gm = "0.4.0.127.0.7.2.2.4.2.4"   // PACE-ECDH-GM-AES-256
    private val im = "0.4.0.127.0.7.2.2.4.4.4"   // PACE-ECDH-IM-AES-256
    private val cam = "0.4.0.127.0.7.2.2.4.6.4"  // PACE-ECDH-CAM-AES-256

    /** Fausse puce : comportement par OID, journal des tentatives et des reconnexions. */
    private class FakeChip(
        val behaviour: Map<String, () -> Unit>,
        val tagStillThere: Boolean,
    ) {
        val attempts = mutableListOf<String>()
        var reconnects = 0
        var now = 0L
        val stepMs = 6_000L

        fun run(infos: List<String>, state: MrzMappingState?, mrz: Boolean = true) = PaceMappingRetry.run(
            infos = infos,
            oidOf = { it },
            state = state,
            stopOnExplicitRefusal = mrz,
            budgetMs = CnieReader.HANDSHAKE_BUDGET_MS,
            attempt = { oid ->
                attempts += oid
                now += stepMs
                (behaviour[oid] ?: {}).invoke()
            },
            reconnect = { reconnects++; tagStillThere },
            clock = { now },
        )
    }

    private val freeze: () -> Unit = { throw TagLostException("gel") }
    private val ok: () -> Unit = {}
    private fun sw(code: Int, step: Int? = null): () -> Unit = {
        throw if (step != null) CardServiceProtocolException("PACE", step, code) else CardServiceException("PACE", code)
    }

    private fun offerCan(e: PaceMappingRetry.Outcome, state: MrzMappingState, freezes: Int) =
        PaceMappingRetry.shouldOfferCan(
            keyRefused = e is PaceMappingRetry.Outcome.KeyRefused || e is PaceMappingRetry.Outcome.ExplicitRefusal,
            mappingsExhausted = state.allTried(),
            freezeCount = freezes,
            freezeLimit = 3,
        )

    @Test
    fun `GM gele puis IM reussit dans le meme tap sans CAN`() {
        val chip = FakeChip(mapOf(gm to freeze, im to ok), tagStillThere = true)
        val state = MrzMappingState()
        val out = chip.run(listOf(gm, im), state)
        assertEquals(PaceMappingRetry.Outcome.Success(PaceMapping.IM), out)
        assertEquals(listOf(gm, im), chip.attempts)
        assertEquals(1, chip.reconnects)
        assertFalse(state.allTried())
    }

    @Test
    fun `GM gele tag perdu puis IM reussit au tap suivant sans CAN`() {
        val chip = FakeChip(mapOf(gm to freeze, im to ok), tagStillThere = false)
        val state = MrzMappingState()
        val tap1 = chip.run(listOf(gm, im), state)
        assertTrue(tap1 is PaceMappingRetry.Outcome.Interrupted)
        assertFalse("un mapping reste : pas de CAN", offerCan(tap1, state, freezes = 1))

        val tap2 = chip.run(listOf(gm, im), state)
        assertEquals(PaceMappingRetry.Outcome.Success(PaceMapping.IM), tap2)
        assertEquals("GM n'est jamais retenté", listOf(gm, im), chip.attempts)
    }

    @Test
    fun `tous les mappings en echec propose le CAN`() {
        val chip = FakeChip(mapOf(gm to freeze, im to freeze, cam to sw(0x6F00)), tagStillThere = false)
        val state = MrzMappingState()
        val infos = listOf(gm, im, cam)
        val tap1 = chip.run(infos, state)
        assertFalse(offerCan(tap1, state, 1))
        val tap2 = chip.run(infos, state)
        assertFalse(offerCan(tap2, state, 2))
        val tap3 = chip.run(infos, state)
        assertTrue(tap3 is PaceMappingRetry.Outcome.Exhausted)
        assertTrue(state.allTried())
        assertTrue(offerCan(tap3, state, 2))
        assertEquals(listOf(gm, im, cam), chip.attempts)
        assertEquals(setOf(PaceMapping.GM, PaceMapping.IM, PaceMapping.CAM), state.failedEver)
    }

    @Test
    fun `refus explicite a MSE Set AT propose le CAN immediatement`() {
        for (code in listOf(0x6A80, 0x6A88, 0x6985)) {
            val chip = FakeChip(mapOf(gm to sw(code, step = 0), im to ok), tagStillThere = true)
            val state = MrzMappingState()
            val out = chip.run(listOf(gm, im), state)
            assertTrue(out is PaceMappingRetry.Outcome.ExplicitRefusal)
            assertTrue(state.explicitRefusal)
            assertEquals("IM n'est pas tenté après un refus explicite", listOf(gm), chip.attempts)
            assertTrue(offerCan(out, state, 0))
        }
    }

    @Test
    fun `6A80 hors MSE Set AT n'est pas un refus explicite`() {
        val chip = FakeChip(mapOf(gm to sw(0x6A80, step = 2), im to ok), tagStillThere = true)
        val out = chip.run(listOf(gm, im), MrzMappingState())
        assertEquals(PaceMappingRetry.Outcome.Success(PaceMapping.IM), out)
    }

    @Test
    fun `63Cx n'est provoque qu'une fois`() {
        val chip = FakeChip(mapOf(gm to sw(0x63C2), im to ok), tagStillThere = true)
        val out = chip.run(listOf(gm, im), MrzMappingState())
        assertTrue(out is PaceMappingRetry.Outcome.KeyRefused)
        assertTrue(PaceError.hasRetryCounter((out as PaceMappingRetry.Outcome.KeyRefused).error))
        assertEquals(listOf(gm), chip.attempts)
        assertEquals(0, chip.reconnects)
    }

    @Test
    fun `pas de boucle - chaque mapping tente une seule fois meme sur taps repetes`() {
        val chip = FakeChip(mapOf(gm to freeze, im to freeze, cam to freeze), tagStillThere = true)
        val state = MrzMappingState()
        repeat(10) { chip.run(listOf(gm, im, cam), state) }
        assertEquals(listOf(gm, im, cam), chip.attempts)
        assertTrue(state.allTried())
    }

    @Test
    fun `budget global - pas de nouveau mapping au dela du budget`() {
        val chip = FakeChip(mapOf(gm to freeze, im to freeze, cam to ok), tagStillThere = true)
        val slow = object {
            fun run(state: MrzMappingState) = PaceMappingRetry.run(
                infos = listOf(gm, im, cam), oidOf = { it }, state = state,
                stopOnExplicitRefusal = true, budgetMs = 10_000,
                attempt = { oid -> chip.attempts += oid; chip.now += 6_000; chip.behaviour.getValue(oid).invoke() },
                reconnect = { true }, clock = { chip.now },
            )
        }
        val state = MrzMappingState()
        val tap1 = slow.run(state)
        assertTrue(tap1 is PaceMappingRetry.Outcome.Interrupted)
        assertEquals(listOf(gm, im), chip.attempts)
        assertEquals(PaceMappingRetry.Outcome.Success(PaceMapping.CAM), slow.run(state))
    }

    @Test
    fun `CAN - un rejet a MSE Set AT passe au protocole suivant`() {
        val chip = FakeChip(mapOf(gm to sw(0x6A80, step = 0), im to ok), tagStillThere = true)
        val out = chip.run(listOf(gm, im), state = null, mrz = false)
        assertEquals(PaceMappingRetry.Outcome.Success(PaceMapping.IM), out)
    }

    @Test
    fun `famille de mapping deduite de l'OID`() {
        assertEquals(PaceMapping.GM, PaceMapping.of("0.4.0.127.0.7.2.2.4.1.2"))
        assertEquals(PaceMapping.GM, PaceMapping.of(gm))
        assertEquals(PaceMapping.IM, PaceMapping.of("0.4.0.127.0.7.2.2.4.3.1"))
        assertEquals(PaceMapping.IM, PaceMapping.of(im))
        assertEquals(PaceMapping.CAM, PaceMapping.of(cam))
        assertEquals(PaceMapping.OTHER, PaceMapping.of("1.2.3"))
    }
}
