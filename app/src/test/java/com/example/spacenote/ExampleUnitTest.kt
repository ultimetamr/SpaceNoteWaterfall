package com.example.spacenote

import com.example.spacenote.content.Bucket
import com.example.spacenote.content.CategoryTaskLists
import com.example.spacenote.content.Note
import com.example.spacenote.content.awaitNextPendingId
import com.example.spacenote.content.isReadyToViewResults
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExampleUnitTest {
    private fun categories() = CategoryTaskLists(
        today = mutableListOf(),
        later = mutableListOf(),
        delegate = mutableListOf(),
        undecided = mutableListOf()
    )

    @Test
    fun schedulerReleasesFirstOfTwentyPendingNotes() = runBlocking {
        assertEquals(1L, awaitNextPendingId(null, (1L..20L).toList(), delayMillis = 0))
    }

    @Test
    fun schedulerDoesNotReleaseWhileAnotherNoteIsActive() = runBlocking {
        assertNull(awaitNextPendingId(activeId = 9L, pendingIds = listOf(1L, 2L), delayMillis = 0))
    }

    @Test
    fun placingAndReclaimingMutatesOnlyIndependentCategoryLists() {
        val lists = categories()
        val first = Note(1, "今天一")
        val second = Note(2, "以后")

        lists.place(first, Bucket.TODAY)
        lists.place(second, Bucket.LATER)
        assertEquals(1, lists.today.size)
        assertEquals(1, lists.later.size)
        assertEquals(0, lists.delegate.size)
        assertEquals(0, lists.undecided.size)

        assertEquals(first, lists.reclaim(first.id))
        assertEquals(0, lists.today.size)
        assertEquals(1, lists.totalSize())
    }

    @Test
    fun movingAcrossCategoriesNeverDuplicatesANote() {
        val lists = categories()
        val note = Note(1, "交给别人")

        lists.place(note, Bucket.TODAY)
        lists.move(note.id, Bucket.DELEGATE)

        assertEquals(0, lists.today.size)
        assertEquals(listOf(note), lists.delegate)
        assertEquals(1, lists.totalSize())
    }

    @Test
    fun completionValidationFillsMissingNotesIntoUndecidedAndMatchesInputTotal() {
        val input = (1L..4L).map { Note(it, "待办 $it") }
        val lists = categories()
        lists.today.add(input[0])
        lists.today.add(input[0]) // Simulate a stale duplicate.
        lists.later.add(input[1])
        lists.delegate.add(Note(99, "不属于本批次"))

        assertEquals(2, lists.reconcileWith(input))
        assertEquals(input.size, lists.totalSize())
        assertEquals(listOf(input[0]), lists.today)
        assertEquals(listOf(input[1]), lists.later)
        assertEquals(listOf(input[2], input[3]), lists.undecided)
    }

    @Test
    fun resultEntryIsEnabledOnlyAfterEveryNoteIsClassifiedAndNoNoteIsActive() {
        assertEquals(true, isReadyToViewResults(4, 0, null, 4))
        assertEquals(false, isReadyToViewResults(4, 1, null, 3))
        assertEquals(false, isReadyToViewResults(4, 0, 4L, 3))
        assertEquals(false, isReadyToViewResults(4, 0, null, 3))
    }

    @Test
    fun reclaimDisablesResultEntryUntilTheNoteIsClassifiedAgain() {
        val lists = categories()
        val notes = listOf(Note(1, "今天"), Note(2, "以后"))
        lists.place(notes[0], Bucket.TODAY)
        lists.place(notes[1], Bucket.LATER)
        assertEquals(true, isReadyToViewResults(notes.size, 0, null, lists.totalSize()))

        val reclaimed = lists.reclaim(notes[0].id)!!
        assertEquals(false, isReadyToViewResults(notes.size, 1, reclaimed.id, lists.totalSize()))

        lists.place(reclaimed, Bucket.DELEGATE)
        assertEquals(true, isReadyToViewResults(notes.size, 0, null, lists.totalSize()))
    }
}
