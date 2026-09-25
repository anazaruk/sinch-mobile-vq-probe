package com.sinch.vqprobe.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** SQLite is the command journal/outbox. Claim precedes every side effect. */
class ProbeStore(context: Context) : SQLiteOpenHelper(context, "probe.db", null, 1) {
    private val secrets = context.getSharedPreferences("device_credentials", Context.MODE_PRIVATE)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE commands (id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL, result TEXT, ack INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, payload TEXT NOT NULL, ack INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE logs (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE NOT NULL, payload TEXT NOT NULL, ack INTEGER NOT NULL DEFAULT 0)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun getMeta(key: String): String? = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    @Synchronized fun putMeta(key: String, value: String?) {
        if (value == null) writableDatabase.delete("meta", "key=?", arrayOf(key))
        else check(writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE) >= 0) { "STORAGE_WRITE_FAILED" }
    }
    @Synchronized fun settings(): JSONObject = getMeta("settings")?.let(::JSONObject) ?: JSONObject()
    @Synchronized fun saveSettings(value: JSONObject) = putMeta("settings", value.toString())
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("probe-device-credentials", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("probe-device-credentials", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun credentials(): JSONObject? {
        val value = secrets.getString("encrypted", null) ?: return null
        // Fail closed on key loss/corruption: explicit re-enrollment is required, never plaintext fallback.
        val parts = value.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))
    }
    @Synchronized fun saveCredentials(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(secrets.edit().putString("encrypted", encoded).commit()) { "CREDENTIAL_STORAGE_FAILED" }
        putMeta("device_id", value.getString("device_id"))
    }
    @Synchronized fun deviceId(): String? {
        getMeta("device_id")?.let { return it }
        // Recover a crash between encrypted credential commit and its metadata mirror.
        val restored = runCatching { credentials()?.optString("device_id")?.takeIf { it.isNotBlank() } }.getOrNull()
        if (restored != null) putMeta("device_id", restored)
        return restored
    }
    @Synchronized fun getCommand(commandId: String): JSONObject? = readableDatabase.rawQuery("SELECT state,payload,result FROM commands WHERE id=?", arrayOf(commandId)).use {
        if (!it.moveToFirst()) null else JSONObject(it.getString(1)).put("journal_state", it.getString(0)).apply { if (!it.isNull(2)) put("result", JSONObject(it.getString(2))) }
    }
    /** NEW, DUPLICATE, or CONFLICT; canonical fingerprint is independent of JSON key order. */
    @Synchronized fun claim(command: JSONObject): String {
        val id = command.getString("command_id")
        val immutable = JSONObject().put("action", command.getString("action")).put("parameters", command.optJSONObject("parameters") ?: JSONObject())
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(canonical(immutable).toByteArray()).joinToString("") { "%02x".format(it) }
        readableDatabase.rawQuery("SELECT fingerprint FROM commands WHERE id=?", arrayOf(id)).use {
            if (it.moveToFirst()) return if (it.getString(0) == fingerprint) "DUPLICATE" else "CONFLICT"
        }
        val payload = JSONObject(command.toString()).put("received_at", Instant.now().toString()).put("execution_at", Instant.now().toString())
        writableDatabase.insertOrThrow("commands", null, ContentValues().apply {
            put("id", id); put("fingerprint", fingerprint); put("state", "EXECUTING"); put("payload", payload.toString()); put("ack", if (id.startsWith("local-")) 1 else 0)
        })
        putMeta("last_command_id", id)
        return "NEW"
    }
    @Synchronized fun completeCommand(commandId: String, status: String, data: JSONObject = JSONObject(), error: String? = null) {
        val command = getCommand(commandId) ?: return
        if (command.optString("journal_state") == "FINAL") return
        val result = JSONObject().put("command_id", commandId).put("action", command.getString("action")).put("status", status)
            .put("received_at", command.getString("received_at")).put("execution_at", command.getString("execution_at"))
            .put("completed_at", Instant.now().toString()).put("data", data)
        if (error != null) result.put("error", error)
        writableDatabase.update("commands", ContentValues().apply { put("state", "FINAL"); put("result", result.toString()) }, "id=?", arrayOf(commandId))
    }
    @Synchronized fun interruptedCommands(): List<String> = readableDatabase.rawQuery("SELECT id FROM commands WHERE state='EXECUTING'", null).use {
        buildList { while (it.moveToNext()) add(it.getString(0)) }
    }
    @Synchronized fun enqueueEvent(event: JSONObject) {
        val row = JSONObject(event.toString())
        // Engineering calls retain their final result and structured log, not a remote outbox.
        if (row.optString("command_id").startsWith("local-")) return
        if (!row.has("event_id")) row.put("event_id", UUID.randomUUID().toString())
        if (!row.has("timestamp")) row.put("timestamp", Instant.now().toString())
        writableDatabase.insertWithOnConflict("events", null, ContentValues().apply {
            put("id", row.getString("event_id")); put("payload", row.toString())
            put("ack", if (row.optString("command_id").startsWith("local-")) 1 else 0)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized fun saveLastCallResult(value: JSONObject) = putMeta("last_call_result", value.toString())
    @Synchronized fun lastCallResult(): JSONObject? = getMeta("last_call_result")?.let(::JSONObject)
    @Synchronized fun addLog(log: JSONObject) {
        writableDatabase.insertOrThrow("logs", null, ContentValues().apply { put("id", log.getString("log_id")); put("payload", log.toString()) })
        // Bounded local diagnostics (commands/results are never evicted automatically).
        writableDatabase.execSQL("DELETE FROM logs WHERE seq <= (SELECT COALESCE(MAX(seq),0)-5000 FROM logs)")
    }
    @Synchronized fun recentLogs(limit: Int): JSONArray = readableDatabase.rawQuery("SELECT payload FROM logs ORDER BY seq DESC LIMIT ?", arrayOf(limit.coerceIn(1, 500).toString())).use {
        JSONArray().apply { while (it.moveToNext()) put(JSONObject(it.getString(0))) }
    }
    @Synchronized fun pending(kind: String, limit: Int = 20): JSONArray {
        val sql = when (kind) {
            "results" -> "SELECT result FROM commands WHERE ack=0 AND state='FINAL' ORDER BY rowid LIMIT ?"
            "events" -> "SELECT payload FROM events WHERE ack=0 ORDER BY rowid LIMIT ?"
            "logs" -> "SELECT payload FROM logs WHERE ack=0 ORDER BY seq LIMIT ?"
            else -> error("INVALID_OUTBOX")
        }
        return readableDatabase.rawQuery(sql, arrayOf(limit.coerceIn(1, 50).toString())).use { JSONArray().apply { while (it.moveToNext()) put(JSONObject(it.getString(0))) } }
    }
    @Synchronized fun acknowledge(kind: String, ids: JSONArray) {
        val table = when(kind) { "results" -> "commands"; "events" -> "events"; "logs" -> "logs"; else -> error("INVALID_OUTBOX") }
        writableDatabase.beginTransaction()
        try {
            for(i in 0 until ids.length()) writableDatabase.update(table, ContentValues().apply {
                put("ack", 1)
                // Keep a tiny event-ID tombstone for replay, release potentially large samples.
                if (kind == "events") put("payload", "{}")
            }, "id=?", arrayOf(ids.getString(i)))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    private fun canonical(value: Any?): String = when(value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
