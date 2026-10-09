package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent names and address registry for imported WIF wallets. */
public final class ImportedWalletStore {
    private static final String PREFS = "imported_wif_wallets";
    private static final String PREFIX = "name.";
    private static final String PREFIX_BIP38 = "bip38.";
    private static final String PREFIX_TYPE = "type.";
    private static final String KEY_ADDRESSES = "addresses";
    private static final String KEY_ADDRESS_LIST = "address_list_v2";

    private ImportedWalletStore() {}

    private static SharedPreferences prefs(Context context) {
        String name = NetworkConfig.get(context) == org.bitcoinj.base.BitcoinNetwork.MAINNET
                ? PREFS
                : PREFS + "_" + NetworkConfig.storageName(NetworkConfig.get(context));
        return context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public static String getName(Context context, String address, int fallbackIndex) {
        if (address == null || address.trim().isEmpty()) {
            return context.getString(R.string.imported_wallet_default_name);
        }
        String cleanAddress = address.trim();
        String value = prefs(context).getString(PREFIX + cleanAddress, null);
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return context.getString(R.string.imported_wallet_default_name_numbered, Math.max(1, fallbackIndex));
    }

    /** Registers an imported address so the management screen can render it immediately. */
    public static synchronized void register(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return;
        String cleanAddress = address.trim();
        SharedPreferences p = prefs(context);
        java.util.LinkedHashSet<String> updated = new java.util.LinkedHashSet<>();
        String encoded = p.getString(KEY_ADDRESS_LIST, null);
        if (encoded != null && !encoded.trim().isEmpty()) {
            for (String item : encoded.split("\\|")) {
                if (!item.trim().isEmpty()) updated.add(item.trim());
            }
        }
        // Migrate addresses written by older builds using StringSet.
        try {
            Set<String> legacy = p.getStringSet(KEY_ADDRESSES, null);
            if (legacy != null) {
                for (String item : legacy) {
                    if (item != null && !item.trim().isEmpty()) updated.add(item.trim());
                }
            }
        } catch (ClassCastException ignored) {
            // KEY_ADDRESSES may already have been changed by a future/older format.
        }
        updated.add(cleanAddress);
        p.edit().putString(KEY_ADDRESS_LIST, join(updated)).apply();
    }

    private static String join(java.util.Collection<String> addresses) {
        StringBuilder out = new StringBuilder();
        for (String address : addresses) {
            if (out.length() > 0) out.append('|');
            out.append(address);
        }
        return out.toString();
    }

    /** Returns the persisted imported-address registry in stable insertion order. */
    public static synchronized List<String> getAddresses(Context context) {
        SharedPreferences p = prefs(context);
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        String encoded = p.getString(KEY_ADDRESS_LIST, null);
        if (encoded != null && !encoded.trim().isEmpty()) {
            for (String item : encoded.split("\\|")) {
                if (!item.trim().isEmpty()) result.add(item.trim());
            }
        }
        try {
            Set<String> legacy = p.getStringSet(KEY_ADDRESSES, null);
            if (legacy != null) {
                for (String item : legacy) {
                    if (item != null && !item.trim().isEmpty()) result.add(item.trim());
                }
            }
        } catch (ClassCastException ignored) {
            // Older installations may store this preference under a different type.
        }
        return new ArrayList<>(result);
    }

    public static void setName(Context context, String address, String name) {
        if (address == null || address.trim().isEmpty() || name == null) return;
        String cleanAddress = address.trim();
        String clean = name.trim();
        if (clean.isEmpty()) return;
        register(context, cleanAddress);
        prefs(context).edit().putString(PREFIX + cleanAddress, clean).apply();
    }

    /** Stores the address/script type used when this imported key was registered. */
    public static void setAddressType(Context context, String address, String type) {
        if (address == null || address.trim().isEmpty()) return;
        String cleanAddress = address.trim();
        register(context, cleanAddress);
        prefs(context).edit().putString(PREFIX_TYPE + cleanAddress,
                WalletAddressType.normalize(type)).apply();
    }

    /** Returns the stored address/script type, defaulting old entries to P2PKH. */
    public static String getAddressType(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return WalletAddressType.P2PKH;
        return WalletAddressType.normalize(
                prefs(context).getString(PREFIX_TYPE + address.trim(), WalletAddressType.P2PKH));
    }

    /** Stores the original BIP38 text for an imported address. */
    public static void setBip38Key(Context context, String address, String encryptedWif) {
        if (address == null || address.trim().isEmpty() || encryptedWif == null
                || encryptedWif.trim().isEmpty()) return;
        String cleanAddress = address.trim();
        register(context, cleanAddress);
        prefs(context).edit().putString(PREFIX_BIP38 + cleanAddress, encryptedWif.trim()).apply();
    }

    /** Returns the original BIP38 text when this address was imported from BIP38. */
    public static String getBip38Key(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return null;
        return prefs(context).getString(PREFIX_BIP38 + address.trim(), null);
    }

    /** Clears BIP38 import metadata when the same key is imported as ordinary WIF. */
    public static void clearBip38Key(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return;
        prefs(context).edit().remove(PREFIX_BIP38 + address.trim()).apply();
    }

    public static void remove(Context context, String address) {
        if (address == null || address.trim().isEmpty()) return;
        String cleanAddress = address.trim();
        List<String> current = getAddresses(context);
        current.remove(cleanAddress);
        prefs(context).edit()
                .remove(PREFIX + cleanAddress)
                .remove(PREFIX_TYPE + cleanAddress)
                .remove(PREFIX_BIP38 + cleanAddress)
                .putString(KEY_ADDRESS_LIST, join(current))
                .apply();
    }
}
