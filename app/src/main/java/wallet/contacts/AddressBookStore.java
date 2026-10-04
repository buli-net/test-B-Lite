package wallet.contacts;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small local address book. It stores labels and public Bitcoin addresses only. */
public final class AddressBookStore {
    private static final String PREFS = "address_book";
    private static final String KEY_ENTRIES = "entries";
    private static final String SEP = "\t";

    private AddressBookStore() {}

    public static List<Entry> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> raw = prefs.getStringSet(KEY_ENTRIES, Collections.<String>emptySet());
        List<Entry> result = new ArrayList<>();
        for (String item : raw) {
            Entry entry = decode(item);
            if (entry != null) result.add(entry);
        }
        Collections.sort(result, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    public static boolean add(Context context, String name, String address) {
        name = name == null ? "" : name.trim();
        address = address == null ? "" : address.trim();
        if (name.length() == 0 || address.length() == 0) return false;
        Set<String> entries = new HashSet<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_ENTRIES, Collections.<String>emptySet()));
        for (String raw : entries) {
            Entry entry = decode(raw);
            if (entry != null && entry.address.equals(address)) return false;
        }
        entries.add(encode(new Entry(name, address)));
        return save(context, entries);
    }

    public static boolean remove(Context context, String address) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> entries = new HashSet<>(prefs.getStringSet(KEY_ENTRIES, Collections.<String>emptySet()));
        boolean changed = false;
        for (String raw : new HashSet<>(entries)) {
            Entry entry = decode(raw);
            if (entry != null && entry.address.equals(address)) {
                entries.remove(raw);
                changed = true;
            }
        }
        return !changed || save(context, entries);
    }

    private static boolean save(Context context, Set<String> entries) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_ENTRIES, entries).commit();
    }

    private static String encode(Entry entry) {
        return b64(entry.name) + SEP + b64(entry.address);
    }

    private static Entry decode(String raw) {
        if (raw == null) return null;
        int split = raw.indexOf(SEP);
        if (split <= 0) return null;
        try {
            String name = fromB64(raw.substring(0, split));
            String address = fromB64(raw.substring(split + SEP.length()));
            if (name.length() == 0 || address.length() == 0) return null;
            return new Entry(name, address);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String b64(String value) {
        return Base64.encodeToString(value.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static String fromB64(String value) {
        return new String(Base64.decode(value, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    public static final class Entry {
        public final String name;
        public final String address;
        public Entry(String name, String address) {
            this.name = name;
            this.address = address;
        }
    }
}
