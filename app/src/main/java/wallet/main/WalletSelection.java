package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.ScriptBuilder;
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
        return context.getApplicationContext().getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
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
        for (ECKey key : wallet.getImportedKeys()) {
            try {
                String address = LegacyAddress.fromKey(wallet.getParams(), key).toString();
                if (selected.equals(address)) {
                    return ScriptBuilder.createOutputScript(LegacyAddress.fromKey(wallet.getParams(), key));
                }
            } catch (Exception ignored) {
            }
        }
        selectMain(context);
        return null;
    }

    public static java.util.List<String> getImportedAddresses(Wallet wallet) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (wallet == null) return result;
        for (ECKey key : wallet.getImportedKeys()) {
            try {
                result.add(LegacyAddress.fromKey(wallet.getParams(), key).toString());
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public static Script findImportedScriptForAddress(Wallet wallet, String address) {
        ECKey key = findImportedKey(wallet, address);
        if (key == null) return null;
        try {
            return ScriptBuilder.createOutputScript(LegacyAddress.fromKey(wallet.getParams(), key));
        } catch (Exception ignored) {
            return null;
        }
    }

    public static ECKey findImportedKey(Wallet wallet, String address) {
        if (wallet == null || address == null || address.trim().isEmpty()) return null;
        for (ECKey key : wallet.getImportedKeys()) {
            try {
                if (address.trim().equals(LegacyAddress.fromKey(wallet.getParams(), key).toString())) {
                    return key;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
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
     * Coin selector restricted to outputs that are not explicitly watch-only.
     * This prevents a watched UTXO from entering a normal send even though
     * bitcoinj's generic wallet balance may include watched outputs.
     */
    public static CoinSelector mainCoinSelector(Context context, Wallet wallet) {
        if (wallet == null) {
            throw new IllegalArgumentException("wallet == null");
        }
        final CoinSelector delegate = wallet.getCoinSelector();
        final Script importedScript = findSelectedImportedScript(context, wallet);
        return (target, candidates) -> {
            java.util.ArrayList<TransactionOutput> filtered = new java.util.ArrayList<>();
            for (TransactionOutput output : candidates) {
                if (importedScript != null) {
                    if (importedScript.equals(output.getScriptPubKey())) {
                        filtered.add(output);
                    }
                } else if (!isWatchedOutput(wallet, output)) {
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
