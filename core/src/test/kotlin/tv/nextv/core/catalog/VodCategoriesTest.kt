package tv.nextv.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Noms propres des catégories de films et de séries, et catégories d'autres pays. */
class VodCategoriesTest {

    /** Catégories de films et de séries d'une vraie playlist (relevées le 18/11/2026). */
    private val playlist = listOf(
            "THRILLER | DRAME | GUERRE",
            "FILMS RÉCEMMENT AJOUTÉS",
            "FILMS EN 4K (HDR) !",
            "SPECTACLES | CONCERTS",
            "COMEDIE",
            "FANTASTIQUE | AVENTURE",
            "VENGEANCE",
            "FRANÇAIS",
            "DOCUMENTAIRES | EMISSION TV",
            "ANIMATION | FAMILIALE | ENFANTS",
            "ROMANCE",
            "3D (MULTI)",
            "UHD IMAX",
            "MUSICAL",
            "SC FICTION | HORREUR",
            "LEGENDAIRES | ANCIENS FILMS",
            "ESPIONNAGE | POLITIQUE",
            "MAFIA | GANG",
            "SURVIVALS | FILMS DE SURVIE",
            "MANGAS",
            "WORKOUT | SPORTS",
            "100% DOLBY VISION HDR10",
            "OSCAR 2026 (NOUVELLE MISE À JOUR)",
            "DRAME | HISTOIRE",
            "VOST ARABIC | أفلام مترجمة",
            "POLICIER | ACTION | CRIME",
            "TÉLÉFILMS",
            "MÉDIÉVALE (MOYEN AGE)",
            "ARTS MARTIAUX",
            "أفلام عربية قديمة | ANCIENS FILMS ARABES",
            "TUEUR EN SERIE",
            "CLASSIC",
            "MARITIME",
            "FILMS DE FIN D’ANNÉE",
            "WESTERN | HISTORIQUE",
            "FILMS MAGHRÈBINS (DZ/MA/TN)",
            "DANCE",
            "VOITURES | CARS",
            "BRAQUAGE",
            "BIOPIC",
            "BOX OFFICE (POPULAIRE EN CE MOMENT)",
            "COMEDIE MUSICAL",
            "COUP DE COEUR",
            "CATASTROPHE",
            "JURIDIQUE",
            "افلام عربية | FILMS ARABES",
            "NOTRE SELECTION DU MOIS ( OCTOBRE )",
            "ARNAQUE",
            "ANIMATION - ENFANT ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "CORÉENNE | KOREA SERIES ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "RECEMMENT AJOUTÉES",
            "DOCUMENTAIRE - BIOPIC ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "RÉALITÉ ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "SUPER-HEROS ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "COMEDIE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "DRAME ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "COMEDIE ANIMATION ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "BRAQUAGE & ARNAQUE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "THRILLER ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "WESTERN - HISTORIQUE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "ACTION ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "HORREUR | MYSTERE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "JEUNESSE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "SPORT ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "POLICIER ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "MEDIEVAL |MOYEN AGE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "ROMANCE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "CRIME ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "ESPIONNAGE & POLITIQUE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "SCIENCE FICTION ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "PRISON ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "AVENTURE | FANTASTIQUE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "MUSICAL ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "MAFIA & GANG ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "70'S | 80'S |OLD| CULTES",
            "4K HDR - MUTLI",
            "مسلسلات عربية | SERIES ARABES",
            "MAGHREBINE | RAMADAN",
            "رمضان 2026 | RAMADAN 2026",
            "JURIDIQUE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "مسلسلات تركية | SERIES TURQUES",
            "MEDECINE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "DISNEY +",
            "TOP SELCTION ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "TUEUR EN SERIE ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )",
            "PL Thriller-Action-Western-War-Horror",
            "PARAMOUNT+",
            "BrutX ORIGINAL",
    )

    @Test
    fun everyCategoryOfARealPlaylistGetsAProperName() {
        for (group in playlist) {
            val name = checkNotNull(VodCategories.properName(group)) { group }
            assertFalse("$group → $name", name.contains("NETFLIX", ignoreCase = true))
            assertFalse("$group → $name", Regex("[\\u0600-\\u06FF]").containsMatchIn(name))
            assertFalse("$group → $name", name.contains('|') || name.contains('!'))
            // Plus de nom tout en majuscules (sauf sigles courts : « UHD IMAX », « Dolby Vision HDR10 »).
            assertTrue("$group → $name", name.any { it.isLowerCase() } || name.length <= 8)
            // Un nom propre reste le même : les catégories masquées sous ce nom le restent.
            assertEquals(group, name, VodCategories.properName(name))
        }
    }

    @Test
    fun knownNames() {
        assertEquals("Science-fiction et horreur", VodCategories.properName("SC FICTION | HORREUR"))
        assertEquals("Drame", VodCategories.properName("DRAME ( NETFLIX| PRIME | HBO | APPLE TV+ | STARZ | PARAMOUNT+ )"))
        assertEquals("Séries turques", VodCategories.properName("مسلسلات تركية | SERIES TURQUES"))
        assertEquals("Films de fin d'année", VodCategories.properName("FILMS DE FIN D’ANNÉE"))
        assertEquals("Sélection du mois", VodCategories.properName("NOTRE SELECTION DU MOIS ( NOVEMBRE )"))
        assertEquals("Oscars 2027", VodCategories.properName("OSCAR 2027 (NOUVELLE MISE À JOUR)"))
        assertEquals("Ramadan 2027", VodCategories.properName("رمضان 2027 | RAMADAN 2027"))
        assertEquals("Disney+", VodCategories.properName("DISNEY +"))
        assertNull(VodCategories.properName(null))
        assertEquals("", VodCategories.properName(""))
    }

    @Test
    fun unknownCapitalNamesAreWrittenNormally() {
        assertEquals("Horreur et gore", VodCategories.properName("HORREUR | GORE"))
        assertEquals("Action, aventure et guerre", VodCategories.properName("ACTION | AVENTURE | GUERRE ( NETFLIX| PRIME )"))
        assertEquals("Séries VF", VodCategories.properName("Séries VF"))
    }

    @Test
    fun foreignCategoriesAreFound() {
        val foreign = playlist.filterNot(VodCategories::isFrench).map { VodCategories.properName(it) }.toSet()
        assertEquals(
            setOf(
                "Films maghrébins", "Films en VOST arabe", "Anciens films arabes", "Films arabes", "Séries maghrébines",
                "Séries polonaises", "Ramadan 2026", "Séries turques", "Séries arabes",
            ),
            foreign,
        )
        // Le nom propre aussi.
        assertFalse(VodCategories.isFrench("Séries turques"))
        assertTrue(VodCategories.isFrench("Séries coréennes"))
        assertTrue(VodCategories.isFrench(null))
    }
}
