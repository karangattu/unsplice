package com.echomute

import android.content.SharedPreferences

class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
    private val temp = mutableMapOf<String, Any?>()
    override fun putString(key: String, value: String?) = apply { temp[key] = value }
    override fun putStringSet(key: String, values: Set<String>?) = apply { temp[key] = values }
    override fun putInt(key: String, value: Int) = apply { temp[key] = value }
    override fun putLong(key: String, value: Long) = apply { temp[key] = value }
    override fun putFloat(key: String, value: Float) = apply { temp[key] = value }
    override fun putBoolean(key: String, value: Boolean) = apply { temp[key] = value }
    override fun remove(key: String) = apply { temp.remove(key) }
    override fun clear() = apply { temp.clear() }
    override fun commit(): Boolean { data.putAll(temp); return true }
    override fun apply() { data.putAll(temp) }
}

class FakePrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun getAll(): Map<String, *> = map
    override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
    override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in map
    override fun edit(): SharedPreferences.Editor = FakeEditor(map)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}
