package fr.veripuce.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vote par position sur le numéro de document (remplace « 2 trames identiques »).
 */
class MrzVoteTest {

    private fun mrz(doc: String) =
        MrzOcr.MrzData(doc, "900713", "300211", MrzOcr.DocType.ID_CARD, "FRA")

    private fun feed(vote: MrzVote, docs: List<String>): MrzVote.Decision? {
        var last: MrzVote.Decision? = null
        docs.forEach { last = vote.offer(mrz(it)) }
        return last
    }

    @Test
    fun `pas de decision avant assez de trames`() {
        val v = MrzVote()
        assertNull(v.offer(mrz("X4RTGPFW4")))
        assertNull(v.offer(mrz("X4RTGPFW4")))  // 2 trames < minVotes=3
    }

    @Test
    fun `erreur intermittente G lu 6 converge vers le bon numero`() {
        // 5 lectures « G », 3 lectures « 6 » : le vote élit « G » (marge 2).
        val v = MrzVote()
        val docs = listOf("X4RTGPFW4", "X4RT6PFW4", "X4RTGPFW4", "X4RTGPFW4",
            "X4RT6PFW4", "X4RTGPFW4", "X4RT6PFW4", "X4RTGPFW4")
        val decision = feed(v, docs)
        assertTrue(decision is MrzVote.Decision.Confident)
        assertEquals("X4RTGPFW4", (decision as MrzVote.Decision.Confident).mrz.documentNumber)
    }

    @Test
    fun `conflit stable 50-50 remonte les deux candidats`() {
        // 4 « G » et 4 « 6 », paire aveugle : ni marge ni départage possible -> les deux.
        val v = MrzVote()
        val docs = listOf("X4RTGPFW4", "X4RT6PFW4", "X4RTGPFW4", "X4RT6PFW4",
            "X4RTGPFW4", "X4RT6PFW4", "X4RTGPFW4", "X4RT6PFW4")
        val decision = feed(v, docs)
        assertTrue("attendu BlindPairConflict, obtenu $decision", decision is MrzVote.Decision.BlindPairConflict)
        decision as MrzVote.Decision.BlindPairConflict
        val all = listOf(decision.mrz.documentNumber) + decision.alternates
        assertTrue(all.contains("X4RTGPFW4"))
        assertTrue(all.contains("X4RT6PFW4"))
    }

    @Test
    fun `ambiguite hors paire aveugle ne tranche pas`() {
        // « A » vs « X » (pas une paire aveugle) à 50-50 : on refuse de deviner.
        val v = MrzVote()
        val docs = listOf("A4RT1PFW4", "X4RT1PFW4", "A4RT1PFW4", "X4RT1PFW4",
            "A4RT1PFW4", "X4RT1PFW4", "A4RT1PFW4", "X4RT1PFW4")
        assertNull(feed(v, docs))
    }

    @Test
    fun `lecture stable et nette est acceptee`() {
        val v = MrzVote()
        val decision = feed(v, List(4) { "X4RTBPFW4" })
        assertEquals("X4RTBPFW4", (decision as MrzVote.Decision.Confident).mrz.documentNumber)
    }

    @Test
    fun `deux documents differents ne se melangent pas dans le vote`() {
        // Une carte A (3 trames) puis une carte B présentée : B ne vote pas avec A.
        val v = MrzVote()
        v.offer(MrzOcr.MrzData("AAAAAAAA1", "900713", "300211", MrzOcr.DocType.ID_CARD, "FRA"))
        v.offer(MrzOcr.MrzData("AAAAAAAA1", "900713", "300211", MrzOcr.DocType.ID_CARD, "FRA"))
        // dates différentes -> autre document -> cohorte insuffisante -> pas de décision
        val d = v.offer(MrzOcr.MrzData("BBBBBBBB2", "850101", "310101", MrzOcr.DocType.ID_CARD, "FRA"))
        assertNull(d)
    }
}
