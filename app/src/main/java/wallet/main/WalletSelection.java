package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.Coin;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.CoinSelector;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.Wallet;

/** Stores and resolves the wallet/address currently selected in the main UI. */
public final class WalletSelection {

    private static final String PREFS = "wallet_selection";
    private static final String KEY_WATCH_ADDRESS = "selected_watch_address";
    private static final String KEY_IMPORTED_ADDRESS = "selected_imported_address";
    private static final String REQUEST_NESTED_PUBKEY_PREFIX = "request_nested_pubkey_";

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

    public static void registerRequestNestedAddress(Context context, String address, byte[] publicKey) {
        if (context == null || address == null || address.trim().isEmpty()
                || publicKey == null || publicKey.length == 0) return;
        prefs(context).edit()
                .putString(REQUEST_NESTED_PUBKEY_PREFIX + address.trim(),
                        android.util.Base64.encodeToString(publicKey, android.util.Base64.NO_WRAP))
                .apply();
    }

    public static ECKey findRequestNestedKeyForScript(
            Context context, Wallet wallet, Script script) {
        if (context == null || wallet == null || script == null) return null;
        String address = addressForScript(script, wallet.getParams());
        if (address == null) return null;

        String encodedPublicKey = prefs(context).getString(
                REQUEST_NESTED_PUBKEY_PREFIX + address, null);
        if (encodedPublicKey != null) {
            try {
                byte[] publicKey = android.util.Base64.decode(
                        encodedPublicKey, android.util.Base64.NO_WRAP);
                ECKey key = findKeyByPublicKey(wallet, publicKey);
                if (key != null && isMatchingNestedScript(wallet, key, script)) {
                    return key;
                }
            } catch (Exception ignored) {
            }
        }

        // Also recognize scripts created before the public key mapping was stored.
        // The key for a request output may be inside the deterministic lookahead window
        // after a mnemonic restore, even though bitcoinj has not issued it yet.
        // Search every active chain's leaf keys so restored P2SH outputs remain spendable.
        for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
            for (DeterministicKey key : chain.getLeafKeys()) {
                if (isMatchingNestedScript(wallet, key, script)) {
                    registerRequestNestedAddress(context, address, key.getPubKey());
                    return key;
                }
            }
        }
        for (ECKey key : wallet.getIssuedReceiveKeys()) {
            if (isMatchingNestedScript(wallet, key, script)) {
                registerRequestNestedAddress(context, address, key.getPubKey());
                return key;
            }
        }
        return null;
    }

    private static ECKey findKeyByPublicKey(Wallet wallet, byte[] publicKey) {
        for (ECKey key : wallet.getIssuedReceiveKeys()) {
            if (java.util.Arrays.equals(key.getPubKey(), publicKey)) return key;
        }
        for (ECKey key : wallet.getImportedKeys()) {
            if (java.util.Arrays.equals(key.getPubKey(), publicKey)) return key;
        }
        return wallet.findKeyFromPubKey(publicKey);
    }

    private static boolean isMatchingNestedScript(Wallet wallet, ECKey key, Script script) {
        try {
            return WalletAddressType.scriptForKey(
                    wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH).equals(script);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isRequestNestedScript(Context context, Wallet wallet, Script script) {
        return findRequestNestedKeyForScript(context, wallet, script) != null;
    }

    public static boolean isReadOnlySelection(Context context, Wallet wallet) {
        Script selected = findSelectedScript(context, wallet);
        return selected != null && !isRequestNestedScript(context, wallet, selected);
    }

    public static String addressForScript(Script script, NetworkParameters parameters) {
        if (script == null || parameters == null) return null;
        try {
            return script.getToAddress(parameters).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Recreates the Nested SegWit watch scripts from the main wallet's deterministic
     * keychains. This is required on mnemonic restore because watched scripts are not
     * derivable from the mnemonic by bitcoinj unless the corresponding scripts are
     * registered again.
     */
    private static java.util.Set<Script> deriveRequestNestedScripts(Wallet wallet) {
        java.util.LinkedHashSet<Script> expected = new java.util.LinkedHashSet<>();
        if (wallet == null) return expected;
        for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
            // Include both issued keys and the current lookahead window. Some bitcoinj
            // versions expose issued receive keys separately from getLeafKeys().
            for (DeterministicKey key : chain.getLeafKeys()) {
                try {
                    expected.add(WalletAddressType.scriptForKey(
                            wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH));
                } catch (Exception ignored) {
                    // One invalid key must not prevent recognition of other scripts.
                }
            }
        }
        for (ECKey key : wallet.getIssuedReceiveKeys()) {
            try {
                expected.add(WalletAddressType.scriptForKey(
                        wallet.getParams(), key, WalletAddressType.P2SH_P2WPKH));
            } catch (Exception ignored) {
                // Keep scanning the remaining issued receive keys.
            }
        }
        return expected;
    }

    /**
     * Builds scripts that are owned by the main deterministic receive keychain.
     * These scripts must never be copied across restore as independent watch-only
     * entries: older versions persisted Request outputs in watchedScripts, even when
     * the private key was already owned by the main wallet.
     */
    public static java.util.Set<Script> getMainWalletReceiveScripts(Wallet wallet) {
        java.util.LinkedHashSet<Script> scripts = new java.util.LinkedHashSet<>();
        if (wallet == null) return scripts;
        try {
            for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
                for (DeterministicKey key : chain.getLeafKeys()) {
                    addReceiveScripts(scripts, wallet, key);
                }
            }
            for (ECKey key : wallet.getIssuedReceiveKeys()) {
                addReceiveScripts(scripts, wallet, key);
            }
        } catch (Exception ignored) {
            // Return only scripts successfully derived; callers retain unknown scripts
            // rather than deleting possible user-owned watch-only entries.
        }
        return scripts;
    }

    private static void addReceiveScripts(
            java.util.Set<Script> scripts, Wallet wallet, ECKey key) {
        if (scripts == null || wallet == null || key == null) return;
        for (String type : new String[]{
                WalletAddressType.P2PKH,
                WalletAddressType.P2WPKH,
                WalletAddressType.P2SH_P2WPKH,
                WalletAddressType.P2TR}) {
            try {
                scripts.add(WalletAddressType.scriptForKey(wallet.getParams(), key, type));
            } catch (Exception ignored) {
                // This wallet/library combination may not support every address type.
            }
        }
    }

    /**
     * Removes obsolete Request-created P2PKH/P2WPKH scripts from the watch-only set.
     * Earlier versions added every non-Legacy Request output to watchedScripts, causing
     * wallet-owned SegWit addresses to appear as separate watch-only balances. The keys
     * remain in the active HD keychain, so bitcoinj can recognize their ordinary P2PKH
     * and P2WPKH outputs directly; only P2SH-P2WPKH still needs explicit script watching.
     *
     * Safe to call on both Mainnet and Signet: scripts are derived using the active wallet's
     * parameters and deterministic keys, not network-specific prefixes or constants.
     */
    public static int removeRequestNativeWatchedScripts(Wallet wallet) {
        if (wallet == null) return 0;
        java.util.Set<Script> walletOwnedNativeScripts = new java.util.HashSet<>();
        try {
            for (DeterministicKeyChain chain : wallet.getActiveKeyChains()) {
                for (DeterministicKey key : chain.getLeafKeys()) {
                    walletOwnedNativeScripts.add(WalletAddressType.scriptForKey(
                            wallet.getParams(), key, WalletAddressType.P2PKH));
                    walletOwnedNativeScripts.add(WalletAddressType.scriptForKey(
                            wallet.getParams(), key, WalletAddressType.P2WPKH));
                }
            }
            for (ECKey key : wallet.getIssuedReceiveKeys()) {
                walletOwnedNativeScripts.add(WalletAddressType.scriptForKey(
                        wallet.getParams(), key, WalletAddressType.P2PKH));
                walletOwnedNativeScripts.add(WalletAddressType.scriptForKey(
                        wallet.getParams(), key, WalletAddressType.P2WPKH));
            }
        } catch (Exception ignored) {
            // Do not modify watch-only state if the HD keychain cannot be inspected safely.
            return 0;
        }

        java.util.List<Script> obsolete = new java.util.ArrayList<>();
        for (Script script : wallet.getWatchedScripts()) {
            if (walletOwnedNativeScripts.contains(script)) obsolete.add(script);
        }
        if (obsolete.isEmpty()) return 0;
        wallet.removeWatchedScripts(obsolete);
        return obsolete.size();
    }

    public static int ensureRequestNestedScripts(Wallet wallet) {
        if (wallet == null) return 0;
        // Migrate Request outputs created by older builds out of the watch-only scope.
        int removed = removeRequestNativeWatchedScripts(wallet);
        java.util.Set<Script> expected;
        try {
            expected = deriveRequestNestedScripts(wallet);
        } catch (Exception ignored) {
            return removed;
        }
        java.util.Set<Script> existing = new java.util.HashSet<>(wallet.getWatchedScripts());
        java.util.List<Script> missing = new java.util.ArrayList<>();
        for (Script script : expected) {
            if (!existing.contains(script)) missing.add(script);
        }
        if (missing.isEmpty()) return removed;
        return removed + wallet.addWatchedScripts(missing);
    }

    /**
     * When an output pays one of the restored Nested SegWit lookahead scripts, mark its
     * deterministic key as used. This advances bitcoinj's lookahead and lets the next
     * batch of P2SH scripts be registered, preserving normal gap-limit discovery.
     */
    public static void observeRequestNestedTransaction(Wallet wallet, Transaction transaction) {
        if (wallet == null || transaction == null) return;
        java.util.List<DeterministicKeyChain> chains = wallet.getActiveKeyChains();
        java.util.Set<Script> watched = new java.util.HashSet<>(wallet.getWatchedScripts());
        boolean advanced = false;
        for (TransactionOutput output : transaction.getOutputs()) {
            Script outputScript = output.getScriptPubKey();
            if (!watched.contains(outputScript)) continue;
            for (DeterministicKeyChain chain : chains) {
                boolean matched = false;
                for (DeterministicKey key : chain.getLeafKeys()) {
                    if (isMatchingNestedScript(wallet, key, outputScript)) {
                        chain.markKeyAsUsed(key);
                        advanced = true;
                        matched = true;
                        break;
                    }
                }
                if (matched) break;
            }
        }
        if (advanced) ensureRequestNestedScripts(wallet);
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
                    // Older versions exposed request-owned Nested SegWit scripts as
                    // separate selector entries. Migrate that selection back to main.
                    if (isRequestNestedScript(context, wallet, script)) {
                        selectMain(context);
                        return null;
                    }
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

    /** Returns request-created Nested SegWit scripts backed by keys in the main wallet. */
    public static java.util.Set<Script> getRequestNestedScripts(Context context, Wallet wallet) {
        java.util.LinkedHashSet<Script> scripts = new java.util.LinkedHashSet<>();
        if (wallet == null) return scripts;
        java.util.Set<Script> expected;
        try {
            expected = deriveRequestNestedScripts(wallet);
        } catch (Exception ignored) {
            return scripts;
        }
        for (Script script : wallet.getWatchedScripts()) {
            // The exact script-set comparison is the fast path. The key lookup also
            // recognizes issued receive keys retained separately by bitcoinj and keys
            // whose public-key mapping was persisted when the Request was created.
            if (expected.contains(script)
                    || (context != null && findRequestNestedKeyForScript(context, wallet, script) != null)) {
                scripts.add(script);
            }
        }
        return scripts;
    }

    private static Coin requestNestedBalance(Context context, Wallet wallet, boolean confirmedOnly) {
        if (context == null || wallet == null) return Coin.ZERO;
        java.util.Set<Script> scripts = getRequestNestedScripts(context, wallet);
        if (scripts.isEmpty()) return Coin.ZERO;
        long total = 0L;
        for (TransactionOutput output : wallet.getWatchedOutputs(false)) {
            if (!output.isAvailableForSpending()
                    || !scripts.contains(output.getScriptPubKey())) continue;
            if (confirmedOnly && output.getParentTransactionDepthInBlocks() <= 0) continue;
            total += output.getValue().value;
        }
        return Coin.valueOf(total);
    }

    /**
     * Main-wallet balances include request-created Nested SegWit outputs, even though
     * bitcoinj tracks their P2SH scripts as watched outputs for synchronization.
     * Other watched outputs remain outside the main-wallet balance.
     */
    public static Coin mainEstimatedBalance(Context context, Wallet wallet) {
        if (wallet == null) return Coin.ZERO;
        return wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE)
                .add(requestNestedBalance(context, wallet, false));
    }

    public static Coin mainAvailableBalance(Context context, Wallet wallet) {
        if (wallet == null) return Coin.ZERO;
        return wallet.getBalance(Wallet.BalanceType.AVAILABLE_SPENDABLE)
                .add(requestNestedBalance(context, wallet, true));
    }

    /** Compatibility overload for callers without an application context. */
    public static Coin mainEstimatedBalance(Wallet wallet) {
        return wallet == null ? Coin.ZERO
                : wallet.getBalance(Wallet.BalanceType.ESTIMATED_SPENDABLE);
    }

    /** Compatibility overload for callers without an application context. */
    public static Coin mainAvailableBalance(Wallet wallet) {
        return wallet == null ? Coin.ZERO
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

}
