package wallet.main;

import android.content.Context;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.LegacyAddress;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;

/** Address/script types supported by the paper-wallet and imported-key flows. */
public final class WalletAddressType {
    public static final String P2PKH = "P2PKH";
    public static final String P2WPKH = "P2WPKH";
    public static final String P2SH_P2WPKH = "P2SH-P2WPKH";
    public static final String P2TR = "P2TR";

    private WalletAddressType() {
    }

    public static String normalize(String type) {
        if (P2WPKH.equals(type)) return P2WPKH;
        if (P2SH_P2WPKH.equals(type)) return P2SH_P2WPKH;
        if (P2TR.equals(type)) return P2TR;
        return P2PKH;
    }

    public static boolean requiresCompressedKey(String type) {
        return !P2PKH.equals(normalize(type));
    }

    /** True when bitcoinj 0.17.1 can natively spend this imported key/address. */
    public static boolean isNativelySpendable(String type) {
        String normalized = normalize(type);
        return P2PKH.equals(normalized) || P2WPKH.equals(normalized);
    }

    public static Address addressForKey(NetworkParameters parameters, ECKey key, String type) {
        String normalized = normalize(type);
        switch (normalized) {
            case P2WPKH:
                return Address.fromKey(parameters, key, ScriptType.P2WPKH);
            case P2TR:
                return Address.fromKey(parameters, key, ScriptType.P2TR);
            case P2SH_P2WPKH:
                return scriptForKey(parameters, key, normalized).getToAddress(parameters);
            case P2PKH:
            default:
                return LegacyAddress.fromKey(parameters, key);
        }
    }

    public static Script scriptForKey(NetworkParameters parameters, ECKey key, String type) {
        String normalized = normalize(type);
        switch (normalized) {
            case P2WPKH:
                return ScriptBuilder.createP2WPKHOutputScript(key);
            case P2SH_P2WPKH:
                return ScriptBuilder.createP2SHOutputScript(
                        ScriptBuilder.createP2WPKHOutputScript(key));
            case P2TR:
                return ScriptBuilder.createOutputScript(
                        Address.fromKey(parameters, key, ScriptType.P2TR));
            case P2PKH:
            default:
                return ScriptBuilder.createP2PKHOutputScript(key);
        }
    }

    public static int labelResId(String type) {
        String normalized = normalize(type);
        if (P2WPKH.equals(normalized)) return R.string.paper_wallet_print_type_p2wpkh;
        if (P2SH_P2WPKH.equals(normalized)) return R.string.paper_wallet_print_type_p2sh_p2wpkh;
        if (P2TR.equals(normalized)) return R.string.paper_wallet_print_type_p2tr;
        return R.string.paper_wallet_print_type_p2pkh;
    }

    public static String label(Context context, String type) {
        return context.getString(labelResId(type));
    }

    public static boolean isSupported(String type) {
        return P2PKH.equals(type) || P2WPKH.equals(type)
                || P2SH_P2WPKH.equals(type) || P2TR.equals(type);
    }
}
