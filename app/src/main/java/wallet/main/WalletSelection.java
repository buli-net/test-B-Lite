package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.CoinSelector;
import org.bitcoinj.wallet.Wallet;

/** Stores and resolves the wallet/address currently selected in the main UI. */
public final class WalletSelection {

    private static final String PREFS = "wallet_selection";
    private static final String KEY_WATCH_ADDRESS = "selected_watch_address";
    private static final String KEY_IMPORTED_ADDRESS = "selected_imported_address";

    private WalletSelection() {
    }

    private static SharedPreferences prefs(Context context) {
        BitcoinNetwork network = NetworkConfig.get(context);
        String name = network == BitcoinNetwork.MAINNET
                ? PREFS
                : PREFS + "_" + NetworkConfig.storageName(network);
        return context.getApplicationContext().getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public static String getSelectedWatchAddress(Context context) {
        String value = prefs(context).getString(KEY_WATCH_ADDRESS, null);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    public static void selectMain(Context context) {
        prefs(context).edit().remove(KEY_WATCH_ADDRESS).remove(KEY_IMPORTED_ADDRESS).apply();
    }

    public static void selectWatchAddress(Context context, String address) {
        if (address == null || address.trim().isEmpty()) {
            selectMain(context);
            return;
        }
        prefs(context).edit()
                .putString(KEY_WATCH_ADDRESS, address.trim())
                .remove(KEY_IMPORTED_ADDRESS)
                .apply();
    }

    public static String getSelectedImportedAddress(Context context) {
        String value = prefs(context).getString(KEY_IMPORTED_ADDRESS, null);
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    public static void selectImportedAddress(Context context, String address) {
        if (address == null || address.trim().isEmpty()) {
            selectMain(context);
            return;
        }
        prefs(context).edit()
                .putString(KEY_IMPORTED_ADDRESS, address.trim())
                .remove(KEY_WATCH_ADDRESS)
                .apply();
    }

    public static Script findSelectedImportedScript(Context context, Wallet wallet) {
        if (wallet == null) return null;
        String selected = getSelectedImportedAddress(context);
        if (selected == null) return null;
        ECKey key = findImportedKey(wallet, selected);
        if (key == null) {
            selectMain(context);
            return null;
        }
        try {
            String type = ImportedWalletStore.getAddressType(context, selected);
            return WalletAddressType.scriptForKey(wallet.getParams(), key, type);
        } catch (Exception ignored) {
            selectMain(context);
            return null;
        }
    }

    /** Returns imported addresses registered by the management screen and backed by wallet keys. */
    public static java.util.List<String> getImportedAddresses(Context context, Wallet wallet) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (wallet == null) return result;
        for (String address : ImportedWalletStore.getAddresses(context)) {
            if (findImportedKey(wallet, address) != null) {
                result.add(address);
            }
        }
        return result;
    }

    /** Legacy fallback for callers that do not have a Context; derives every supported address. */
    public static java.util.List<String> getImportedAddresses(Wallet wallet) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        if (wallet == null) return new java.util.ArrayList<>(result);
        for (ECKey key : wallet.getImportedKeys()) {
            for (String type : new String[]{
                    WalletAddressType.P2PKH,
                    WalletAddressType.P2WPKH,
                    WalletAddressType.P2SH_P2WPKH,
                    WalletAddressType.P2TR}) {
                try {
                    result.add(WalletAddressType.addressForKey(wallet.getParams(), key, type).toString());
                } catch (Exception ignored) {
                }
            }
        }
        return new java.util.ArrayList<>(result);
    }

    /** Resolves an imported address to its exact output script by testing all supported key/address forms. */
    public static Script findImportedScriptForAddress(Wallet wallet, String address) {
        ECKey key = findImportedKey(wallet, address);
        if (key == null) return null;
        for (String type : new String[]{
                WalletAddressType.P2PKH,
                WalletAddressType.P2WPKH,
                WalletAddressType.P2SH_P2WPKH,
                WalletAddressType.P2TR}) {
            try {
                org.bitcoinj.base.Address derived =
                        WalletAddressType.addressForKey(wallet.getParams(), key, type);
                if (address.trim().equals(derived.toString())) {
                    return WalletAddressType.scriptForKey(wallet.getParams(), key, type);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Finds the private key for any imported address generated by the supported address types. */
    public static ECKey findImportedKey(Wallet wallet, String address) {
        if (wallet == null || address == null || address.trim().isEmpty()) return null;
        String cleanAddress = address.trim();
        for (ECKey key : wallet.getImportedKeys()) {
            for (String type : new String[]{
                    WalletAddressType.P2PKH,
                    WalletAddressType.P2WPKH,
                    WalletAddressType.P2SH_P2WPKH,
                    WalletAddressType.P2TR}) {
                try {
                    if (cleanAddress.equals(
                            WalletAddressType.addressForKey(wallet.getParams(), key, type).toString())) {
                        return key;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    public static boolean selectedImportedUsesWatchedOutputs(Context context) {
        String address = getSelectedImportedAddress(context);
        return address != null && !WalletAddressType.isNativelySpendable(
                ImportedWalletStore.getAddressType(context, address));
    }

    public static boolean importedAddressUsesWatchedOutputs(Context context, String address) {
        return address != null && !WalletAddressType.isNativelySpendable(
                ImportedWalletStore.getAddressType(context, address));
    }

    public static Coin selectedImportedAvailableBalance(Wallet wallet, Script script) {
        if (wallet == null || script == null) return Coin.ZERO;
        Coin total = Coin.ZERO;
        for (TransactionOutput output : wallet.getUnspents()) {
            if (script.equals(output.getScriptPubKey()) && output.isAvailableForSpending()
                    && output.getParentTransactionDepthInBlocks() > 0) {
                total = total.add(output.getValue());
            }
        }
        return total;
    }

    public static Coin selectedImportedAvailableBalance(Context context, Wallet wallet, Script script) {
        if (wallet == null || script == null) return Coin.ZERO;
        Coin total = Coin.ZERO;
        Iterable<TransactionOutput> outputs = selectedImportedUsesWatchedOutputs(context)
                ? wallet.getWatchedOutputs(false) : wallet.getUnspents();
        for (TransactionOutput output : outputs) {
            if (script.equals(output.getScriptPubKey()) && output.isAvailableForSpending()
                    && output.getParentTransactionDepthInBlocks() > 0) {
                total = total.add(output.getValue());
            }
        }
        return total;
    }

    /** Returns true when another registered imported address still uses this key. */
    public static boolean isKeyUsedByOtherImportedAddresses(
            Context context, Wallet wallet, String removedAddress, ECKey key) {
        if (wallet == null || key == null) return false;
        for (String address : ImportedWalletStore.getAddresses(context)) {
            if (address.equals(removedAddress)) continue;
            ECKey other = findImportedKey(wallet, address);
            if (other != null && other.getPubKeyPoint().equals(key.getPubKeyPoint())) {
                return true;
            }
        }
        return false;
    }

    /** Returns the selected watch-only script, or null when the main wallet is selected. */
    public static Script findSelectedScript(Context context, Wallet wallet) {
        if (wallet == null) {
            return null;
        }

        String selected = getSelectedWatchAddress(context);
        if (selected == null) {
            return null;
        }

        NetworkParameters parameters = wallet.getParams();
        for (Script script : wallet.getWatchedScripts()) {
            try {
                String address = script.getToAddress(parameters).toString();
                if (selected.equals(address)) {
                    return script;
                }
            } catch (Exception ignored) {
                // A watched script without an address representation cannot be selected here.
            }
        }

        // The selected address may have been deleted. Fall back to the main wallet.
        selectMain(context);
        return null;
    }

    /** Returns true when the output belongs to one of the explicitly watched scripts. */
    public static boolean isWatchedOutput(Wallet wallet, TransactionOutput output) {
        return wallet != null && output != null
                && isWatchedScript(wallet, output.getScriptPubKey());
    }

    /** Returns true when the script is explicitly watched by the wallet. */
    public static boolean isWatchedScript(Wallet wallet, Script script) {
        if (wallet == null || script == null) {
            return false;
        }
        return wallet.getWatchedScripts().contains(script);
    }

    /**
     * Main-wallet balances exclude explicitly watched outputs. bitcoinj provides
     * SPENDABLE balance types specifically for this distinction.
     */
    public static Coin mainEstimatedBalance(Wallet wallet) {
        return wallet == null
                ? Coin.ZERO
                : wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE);
    }

    public static Coin mainAvailableBalance(Wallet wallet) {
        return wallet == null
                ? Coin.ZERO
                : wallet.getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE);
    }

    /**
     * Returns the scripts belonging to every registered imported wallet.
     *
     * Main-wallet operations must exclude these scripts entirely. An imported
     * wallet is a separate wallet scope even when its key also lives inside
     * the same bitcoinj Wallet instance.
     */
    public static java.util.Set<Script> getImportedScripts(Context context, Wallet wallet) {
        java.util.HashSet<Script> scripts = new java.util.HashSet<>();
        if (context == null || wallet == null) {
            return scripts;
        }
        for (String address : ImportedWalletStore.getAddresses(context)) {
            Script script = findImportedScriptForAddress(wallet, address);
            if (script != null) {
                scripts.add(script);
            }
        }
        return scripts;
    }

    /**
     * Coin selector restricted to the currently selected wallet scope.
     *
     * The main wallet excludes both explicitly watched outputs and every
     * registered imported-wallet output. When an imported wallet is selected,
     * only that wallet's exact script is eligible.
     */
    public static CoinSelector mainCoinSelector(Context context, Wallet wallet) {
        if (wallet == null) {
            throw new IllegalArgumentException("wallet == null");
        }
        final CoinSelector delegate = wallet.getCoinSelector();
        final Script importedScript = findSelectedImportedScript(context, wallet);
        final java.util.Set<Script> importedScripts = importedScript == null
                ? getImportedScripts(context, wallet)
                : java.util.Collections.emptySet();
        return (target, candidates) -> {
            java.util.ArrayList<TransactionOutput> filtered = new java.util.ArrayList<>();
            for (TransactionOutput output : candidates) {
                if (importedScript != null) {
                    if (importedScript.equals(output.getScriptPubKey())) {
                        filtered.add(output);
                    }
                } else if (!isWatchedOutput(wallet, output)
                        && !importedScripts.contains(output.getScriptPubKey())) {
                    filtered.add(output);
                }
            }
            return delegate.select(target, filtered);
        };
    }

    public static String addressForScript(Script script, NetworkParameters parameters) {
        if (script == null || parameters == null) {
            return null;
        }
        try {
            return script.getToAddress(parameters).toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
