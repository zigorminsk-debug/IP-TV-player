package com.iptvplayer.app

import com.iptvplayer.app.data.epg.EpgCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgCatalogTest {

    private fun prog(id: String, start: Long, stop: Long, title: String) =
        EpgCatalog.Programme(channelId = id, start = start, stop = stop, title = title)

    private fun sample(): EpgCatalog = EpgCatalog(
        channels = listOf(
            EpgCatalog.Channel("ch1", listOf("Первый канал", "Channel One"), "http://logo/1.png"),
            EpgCatalog.Channel("ch2", listOf("Матч Премьер HD")),
            EpgCatalog.Channel("ch3", listOf("Киноёжка")),
        ),
        programmes = listOf(
            prog("ch1", 1000, 2000, "Новости"),
            prog("ch1", 2000, 3000, "Фильм: возвращение"),
            prog("ch1", 3600, 4000, "Поздние новости"), // разрыв 3000..3600
            prog("ch2", 1500, 2500, "Футбол: обзор"),
            prog("ch1", 1000, 2000, "Новости"),          // точный дубликат
            prog("ch1", 500, 400, "сломанная строка"),    // stop <= start
        ),
    )

    @Test
    fun invalidAndDuplicateRowsAreIgnored() {
        val c = sample()
        assertEquals(3, c.channelCount)
        assertEquals(4, c.programmeCount)
        assertEquals("Матч Премьер HD", c.channel("ch2")?.displayName)
        assertNull(c.channel("missing"))
    }

    @Test
    fun resolvesExplicitIdsAndUnknownFallsThrough() {
        val c = sample()
        assertEquals("ch1", c.resolveEpgId(tvgId = "ch1"))
        assertEquals("ch2", c.resolveEpgId(epgChannelId = "ch2"))
        assertNull(c.resolveEpgId(tvgId = "missing-id"))
        // unknown id must not shadow a good display-name match
        assertEquals("ch1", c.resolveEpgId(tvgId = "wrong", channelName = "Channel One"))
    }

    @Test
    fun nameMatchingIgnoresCaseYoAndQualitySuffix() {
        val c = sample()
        assertEquals("ch1", c.resolveEpgId(channelName = "  первый   КАНАЛ "))
        assertEquals("ch1", c.resolveEpgId(channelName = "Первый канал (FHD)"))
        assertEquals("ch2", c.resolveEpgId(channelName = "матч премьер"))
        assertEquals("ch2", c.resolveEpgId(channelName = "МАТЧ ПРЕМЬЕР 4K HEVC"))
        assertEquals("ch3", c.resolveEpgId(channelName = "киноежка"))
        assertNull(c.resolveEpgId(channelName = "Несуществующий ТВ"))
        assertNull(c.resolveEpgId())
    }

    @Test
    fun currentProgrammeBoundariesAndGaps() {
        val c = sample()
        assertEquals("Новости", c.currentProgramme("ch1", 1000)?.title) // start включительно
        assertEquals("Новости", c.currentProgramme("ch1", 1999)?.title)
        assertEquals("Фильм: возвращение", c.currentProgramme("ch1", 2000)?.title)
        assertNull(c.currentProgramme("ch1", 3000))   // точный stop → уже не в эфире
        assertNull(c.currentProgramme("ch1", 3300))   // внутри разрыва
        assertNull(c.currentProgramme("unknown", 1500))
    }

    @Test
    fun nextProgrammeIsStrictlyAfter() {
        val c = sample()
        assertEquals("Фильм: возвращение", c.nextProgramme("ch1", 1500)?.title)
        assertEquals("Поздние новости", c.nextProgramme("ch1", 2000)?.title)
        assertNull(c.nextProgramme("ch1", 4000))
    }

    @Test
    fun nowNextPairsForUi() {
        val c = sample()
        val nn = c.nowNext("ch1", 1500)
        assertEquals("Новости", nn.now?.title)
        assertEquals("Фильм: возвращение", nn.next?.title)
        assertEquals(1000L, nn.now?.start)
        assertEquals(3000L, nn.next?.stop)

        val empty = c.nowNext("unknown", 1500)
        assertNull(empty.now)
        assertNull(empty.next)
    }

    @Test
    fun progressOfCurrentProgramme() {
        val c = sample()
        assertEquals(0.5f, c.progress("ch1", 1500)!!, 0.0001f)
        assertTrue(c.progress("ch1", 1999)!! in 0f..1f)
        assertNull(c.progress("ch1", 3300))
    }

    @Test
    fun scheduleOverlapWindow() {
        val c = sample()
        assertEquals(
            listOf("Новости", "Фильм: возвращение", "Поздние новости"),
            c.schedule("ch1", 0, 10_000).map { it.title },
        )
        // окно, касающееся границ, оставляет пересекающиеся передачи
        assertEquals(
            listOf("Новости", "Фильм: возвращение"),
            c.schedule("ch1", 1999, 2001).map { it.title },
        )
        // окно целиком внутри разрыва ничего не видит
        assertTrue(c.schedule("ch1", 3100, 3500).isEmpty())
        assertTrue(c.schedule("ch1", 2000, 1000).isEmpty()) // перевёрнутое окно
        assertTrue(c.schedule("unknown", 0, 10_000).isEmpty())
    }

    @Test
    fun searchesTitlesNormalizedWithLimitAndFromFilter() {
        val c = sample()
        assertEquals(
            listOf("Новости", "Поздние новости"),
            c.searchTitles("новости").map { it.title },
        )
        assertEquals(1, c.searchTitles("ФУТБОЛ: Обзор").size)
        assertTrue(c.searchTitles("н").isEmpty()) // минимум 2 символа
        assertTrue(c.searchTitles("   ").isEmpty())
        // fromSec оставляет только передачи, заканчивающиеся не раньше фильтра
        assertEquals(
            listOf("Поздние новости"),
            c.searchTitles("новости", fromSec = 2500).map { it.title },
        )
        assertEquals(1, c.searchTitles("новости", limit = 1).size)
    }

    @Test
    fun normalizationRules() {
        assertEquals("первый канал", EpgCatalog.normalizeName("Первый Канал HD"))
        assertEquals("первый канал", EpgCatalog.normalizeName("Первый канал (4K)"))
        assertEquals("матч премьер", EpgCatalog.normalizeName("Матч Премьер UHD HEVC"))
        assertEquals("россия 24", EpgCatalog.normalizeName("Россия-24 HD"))
        assertEquals("киноежка", EpgCatalog.normalizeName("КиноЁжка"))
        // никогда не сжимается в пустую строку
        assertEquals("hd", EpgCatalog.normalizeName("HD"))
        assertEquals("", EpgCatalog.normalizeName("   "))
        assertEquals("новости 2026", EpgCatalog.normalizeText("  НОВОСТИ // 2026 "))
    }

    @Test
    fun emptyCatalogIsSafe() {
        val c = EpgCatalog()
        assertEquals(0, c.channelCount)
        assertEquals(0, c.programmeCount)
        assertNull(c.channel("x"))
        assertNull(c.currentProgramme("x", 100))
        assertTrue(c.schedule("x", 0, 100).isEmpty())
        assertTrue(c.searchTitles("новости").isEmpty())
        assertNull(c.resolveEpgId(channelName = "x"))
    }
}
