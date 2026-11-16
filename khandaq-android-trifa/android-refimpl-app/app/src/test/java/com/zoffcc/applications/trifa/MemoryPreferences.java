package com.zoffcc.applications.trifa;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** SharedPreferences in memory; each apply or commit of an editor counts as one commit. */
final class MemoryPreferences implements SharedPreferences
{
    final Map<String, Object> values = new HashMap<>();
    int commits;

    @Override
    public Map<String, ?> getAll()
    {
        return new HashMap<>(values);
    }

    @Override
    public String getString(final String key, final String defValue)
    {
        return values.containsKey(key) ? (String) values.get(key) : defValue;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(final String key, final Set<String> defValues)
    {
        return values.containsKey(key) ? (Set<String>) values.get(key) : defValues;
    }

    @Override
    public int getInt(final String key, final int defValue)
    {
        return values.containsKey(key) ? (Integer) values.get(key) : defValue;
    }

    @Override
    public long getLong(final String key, final long defValue)
    {
        return values.containsKey(key) ? (Long) values.get(key) : defValue;
    }

    @Override
    public float getFloat(final String key, final float defValue)
    {
        return values.containsKey(key) ? (Float) values.get(key) : defValue;
    }

    @Override
    public boolean getBoolean(final String key, final boolean defValue)
    {
        return values.containsKey(key) ? (Boolean) values.get(key) : defValue;
    }

    @Override
    public boolean contains(final String key)
    {
        return values.containsKey(key);
    }

    @Override
    public Editor edit()
    {
        return new MemoryEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener)
    {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(final OnSharedPreferenceChangeListener listener)
    {
    }

    private final class MemoryEditor implements Editor
    {
        private final Map<String, Object> changes = new HashMap<>();

        @Override
        public Editor putString(final String key, final String value)
        {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(final String key, final Set<String> value)
        {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putInt(final String key, final int value)
        {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(final String key, final long value)
        {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(final String key, final float value)
        {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(final String key, final boolean value)
        {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor remove(final String key)
        {
            changes.put(key, null);
            return this;
        }

        @Override
        public Editor clear()
        {
            values.clear();
            return this;
        }

        @Override
        public boolean commit()
        {
            for (final Map.Entry<String, Object> change : changes.entrySet())
            {
                if (change.getValue() == null)
                {
                    values.remove(change.getKey());
                }
                else
                {
                    values.put(change.getKey(), change.getValue());
                }
            }
            commits++;
            return true;
        }

        @Override
        public void apply()
        {
            commit();
        }
    }
}
