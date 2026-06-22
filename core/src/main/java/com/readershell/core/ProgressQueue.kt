package com.readershell.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Local progress store. Every POST /api/progress writes here first; the sync
 * worker forwards dirty rows to cloud when reachable. Merge on read uses
 * last-writer-wins by `updated`.
 *
 * Schema: one row per progress key. payload is the full JSON the server expects.
 */
class ProgressQueue(ctx: Context, namespace: String) :
    SQLiteOpenHelper(ctx, "progress_$namespace.db", null, 1) {

    override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE progress (
                  key TEXT PRIMARY KEY,
                  payload TEXT NOT NULL,
                  updated REAL NOT NULL,
                  dirty INTEGER NOT NULL DEFAULT 1
               )"""
        )
    }
    override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldV: Int, newV: Int) {}

    fun upsert(key: String, payload: JSONObject, updated: Double, dirty: Boolean = true) {
        writableDatabase.insertWithOnConflict(
            "progress",
            null,
            ContentValues().apply {
                put("key", key)
                put("payload", payload.toString())
                put("updated", updated)
                put("dirty", if (dirty) 1 else 0)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun get(key: String): Row? = readableDatabase.rawQuery(
        "SELECT payload, updated, dirty FROM progress WHERE key = ?",
        arrayOf(key),
    ).use { c ->
        if (!c.moveToFirst()) null
        else Row(c.getString(0), c.getDouble(1), c.getInt(2) != 0)
    }

    fun all(): List<KeyedRow> = readableDatabase.rawQuery(
        "SELECT key, payload, updated, dirty FROM progress", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                KeyedRow(c.getString(0), c.getString(1), c.getDouble(2), c.getInt(3) != 0)
            )
        }
    }

    fun dirtyRows(): List<KeyedRow> = all().filter { it.dirty }

    fun markClean(key: String) {
        writableDatabase.execSQL("UPDATE progress SET dirty = 0 WHERE key = ?", arrayOf(key))
    }

    fun delete(key: String) {
        writableDatabase.delete("progress", "key = ?", arrayOf(key))
    }

    data class Row(val payload: String, val updated: Double, val dirty: Boolean)
    data class KeyedRow(val key: String, val payload: String, val updated: Double, val dirty: Boolean)
}
