package com.ml.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.ml.app.domain.CardType

class CardTypeStore(private val context: Context) {

  private fun openRw(): SQLiteDatabase {
    val f = PackDbSync.dbFileToUse(context)
    val db = SQLiteDatabase.openDatabase(f.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
    // ensure table exists
    db.execSQL(
      """
      CREATE TABLE IF NOT EXISTS bag_card_type(
        bag_id TEXT PRIMARY KEY,
        type TEXT NOT NULL
      );
      """.trimIndent()
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS idx_bag_card_type_type ON bag_card_type(type);")
    return db
  }

  fun getTypes(bagIds: List<String>): Map<String, CardType> {
    if (bagIds.isEmpty()) return emptyMap()
    val out = HashMap<String, CardType>(bagIds.size)

    openRw().use { db ->
      val placeholders = bagIds.joinToString(",") { "?" }
      val sql = "SELECT bag_id, type FROM bag_card_type WHERE bag_id IN ($placeholders)"
      db.rawQuery(sql, bagIds.toTypedArray()).use { c ->
        val iId = c.getColumnIndexOrThrow("bag_id")
        val iType = c.getColumnIndexOrThrow("type")
        while (c.moveToNext()) {
          val id = c.getString(iId)
          val t = when (c.getString(iType)) {
            "premium" -> CardType.PREMIUM
            else -> CardType.CLASSIC
          }
          out[id] = t
        }
      }
    }

    for (id in bagIds) if (!out.containsKey(id)) out[id] = CardType.CLASSIC
    return out
  }

  // На будущее (когда добавим редактирование карточки)
  fun setType(bagId: String, type: CardType) {
    val v = if (type == CardType.PREMIUM) "premium" else "classic"
    openRw().use { db ->
      db.execSQL(
        "INSERT INTO bag_card_type(bag_id,type) VALUES(?,?) " +
          "ON CONFLICT(bag_id) DO UPDATE SET type=excluded.type",
        arrayOf(bagId, v)
      )
    }
  }
}
