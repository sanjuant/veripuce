package fr.veripuce.app.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JournalStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun store(max: Int = 200) = JournalStore(File(tmp.root, "journal/lectures.txt"), max)

    @Test
    fun `ajout, numerotation et export`() {
        val s = store()
        assertNull(s.export())
        s.append("etiquette: a\nclassification: succès")
        s.append("etiquette: b")
        assertEquals(2, s.count())
        val out = s.export()!!
        assertTrue(out.startsWith(JournalRedaction.SCHEMA))
        assertTrue(out.contains("===== lecture #1 =====\netiquette: a\nclassification: succès"))
        assertTrue(out.contains("===== lecture #2 =====\netiquette: b"))
    }

    @Test
    fun `plafond - seules les dernieres entrees restent, la numerotation continue`() {
        val s = store(max = 3)
        repeat(5) { s.append("entree ${it + 1}") }
        assertEquals(3, s.count())
        val out = s.export()!!
        assertFalse(out.contains("entree 2"))
        assertTrue(out.contains("===== lecture #5 =====\nentree 5"))
        assertTrue(out.contains("===== lecture #3 =====\nentree 3"))
    }

    @Test
    fun `vider`() {
        val s = store()
        s.append("x")
        s.clear()
        assertEquals(0, s.count())
        assertNull(s.export())
        s.append("y")
        assertTrue(s.export()!!.contains("===== lecture #1 ====="))
    }
}
