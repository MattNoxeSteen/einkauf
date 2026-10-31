package de.matze.einkauf

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Speichert Liste und Laufweg in SharedPreferences (gleiche Schlüssel wie die Web-App). */
class Storage(context: Context) {
    private val prefs = context.getSharedPreferences("einkauf", Context.MODE_PRIVATE)

    fun loadItems(): MutableList<Item> {
        val out = mutableListOf<Item>()
        try {
            val arr = JSONArray(prefs.getString(KEY_ITEMS, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Item(
                        id = o.optString("id", newId()),
                        text = o.optString("text", ""),
                        store = if (o.isNull("store")) null else o.optString("store").ifEmpty { null },
                        cat = if (o.isNull("cat")) null else o.optString("cat").ifEmpty { null }
                    )
                )
            }
        } catch (e: Exception) { /* leere Liste */ }
        return out.filter { it.text.isNotBlank() }.toMutableList()
    }

    fun saveItems(items: List<Item>) {
        val arr = JSONArray()
        for (it in items) {
            val o = JSONObject()
            o.put("id", it.id)
            o.put("text", it.text)
            o.put("store", it.store ?: JSONObject.NULL)
            o.put("cat", it.cat ?: JSONObject.NULL)
            arr.put(o)
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply()
    }

    fun loadOrder(): MutableList<String> {
        val ids = Parser.CAT_IDS
        try {
            val raw = prefs.getString(KEY_ORDER, null) ?: return ids.toMutableList()
            val arr = JSONArray(raw)
            val out = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val id = arr.optString(i)
                if (id in ids && id !in out) out.add(id)
            }
            // neue Bereiche an ihrer Standardposition einfügen
            ids.forEachIndexed { i, id ->
                if (id in out) return@forEachIndexed
                val prev = ids.subList(0, i).reversed().firstOrNull { it in out }
                out.add(if (prev != null) out.indexOf(prev) + 1 else 0, id)
            }
            return out
        } catch (e: Exception) {
            return ids.toMutableList()
        }
    }

    fun saveOrder(order: List<String>) {
        prefs.edit().putString(KEY_ORDER, JSONArray(order).toString()).apply()
    }

    companion object {
        private const val KEY_ITEMS = "einkauf-items-v1"
        private const val KEY_ORDER = "einkauf-reihenfolge-v1"

        fun newId(): String =
            java.lang.Long.toString(System.currentTimeMillis(), 36) +
                java.lang.Long.toString((Math.random() * 1_679_616).toLong(), 36)

        /** Liest eine flache JSON-Map bzw. -Liste aus den Assets. */
        fun loadKnown(context: Context): Map<String, String> {
            val o = JSONObject(context.assets.open("known.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val map = HashMap<String, String>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                map[k] = o.getString(k)
            }
            return map
        }

        fun loadQuick(context: Context): List<String> {
            val a = JSONArray(context.assets.open("quick.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            return (0 until a.length()).map { a.getString(it) }
        }
    }
}
