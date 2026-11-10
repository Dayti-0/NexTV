package tv.nextv.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chaque version du schéma a sa migration : une version augmentée sans elle empêcherait l'application de
 * démarrer (les données ne sont plus effacées en silence), ce test l'attrape avant la publication.
 */
class MigrationsTest {

    @Test
    fun everySchemaVersionHasItsMigration() {
        val expected = (NexTvDatabase.FIRST_MIGRATED_VERSION until NexTvDatabase.VERSION).map { it to it + 1 }
        assertEquals(expected, NexTvDatabase.MIGRATIONS.map { it.startVersion to it.endVersion })
    }

    @Test
    fun theSchemaOfTheCurrentVersionIsKept() {
        // Écrit par la compilation dans app/schemas, à ajouter au dépôt avec la version : la migration suivante
        // s'écrit (et se teste) à partir de lui.
        val schema = File("schemas/tv.nextv.app.data.db.NexTvDatabase/${NexTvDatabase.VERSION}.json")
        assertTrue("schéma absent : ${schema.absolutePath}", schema.isFile)
    }
}
