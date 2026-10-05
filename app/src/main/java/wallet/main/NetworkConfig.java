package wallet.main;

import android.content.Context;
import android.content.SharedPreferences;

import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.HDPath;
import org.bitcoinj.wallet.KeyChainGroupStructure;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.params.SigNetParams;

import java.io.File;
import java.util.Locale;

/** Central network selection and per-network wallet storage configuration. */
public final class NetworkConfig {
    private static final String PREFS = "bitcoin_network";
    private static final String KEY_ACTIVE_NETWORK = "active_network";
    private static final String NETWORKS_DIR = "networks";

    private NetworkConfig() {
    }

    public static BitcoinNetwork get(Context context) {
        if (context == null) {
            return BitcoinNetwork.MAINNET;
        }
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(KEY_ACTIVE_NETWORK, BitcoinNetwork.MAINNET.name());
        try {
            BitcoinNetwork network = BitcoinNetwork.valueOf(value);
            return isSupported(network) ? network : BitcoinNetwork.MAINNET;
        } catch (Exception ignored) {
            return BitcoinNetwork.MAINNET;
        }
    }

    public static void set(Context context, BitcoinNetwork network) {
        if (context == null || !isSupported(network)) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_NETWORK, network.name())
                .commit();
    }

    public static NetworkParameters parameters(Context context) {
        return parameters(get(context));
    }

    /**
     * Returns the HD wallet structure for the selected network. bitcoinj 0.17.1
     * predates the Signet mapping in KeyChainGroupStructure.coinType(), so its
     * stock BIP43 structure throws "coinType: Unknown network" for Signet.
     * Keep the normal BIP43 structure everywhere else and explicitly use the
     * BIP44/84 test-network coin type (1') for Signet.
     */
    public static KeyChainGroupStructure keyChainGroupStructure(BitcoinNetwork network) {
        if (network != BitcoinNetwork.SIGNET) {
            return KeyChainGroupStructure.BIP43;
        }

        return new KeyChainGroupStructure() {
            @Override
            public HDPath accountPathFor(
                    ScriptType outputScriptType,
                    org.bitcoinj.base.Network selectedNetwork) {
                if (selectedNetwork == BitcoinNetwork.SIGNET) {
                    return KeyChainGroupStructure.purpose(outputScriptType)
                            .extend(ChildNumber.ONE_HARDENED, KeyChainGroupStructure.account(0));
                }
                return KeyChainGroupStructure.BIP43.accountPathFor(outputScriptType, selectedNetwork);
            }
        };
    }

    public static NetworkParameters parameters(BitcoinNetwork network) {
        if (network == BitcoinNetwork.SIGNET) {
            // Resolve Signet directly instead of going through the generic
            // NetworkParameters registry. This is important on Android/R8,
            // where registry-based network resolution can otherwise report
            // Signet as an unknown network at runtime.
            return SigNetParams.get();
        }
        return MainNetParams.get();
    }

    /**
     * Keeps the existing Mainnet wallet in the app's original files directory for
     * backward compatibility. Signet gets its own directory and SPV chain.
     */
    public static File walletDirectory(Context context, BitcoinNetwork network) {
        if (!isSupported(network)) {
            network = BitcoinNetwork.MAINNET;
        }
        File filesDir = context.getApplicationContext().getFilesDir();
        if (network == BitcoinNetwork.MAINNET) {
            return filesDir;
        }
        File dir = new File(filesDir, NETWORKS_DIR + File.separator + storageName(network));
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static boolean isSupported(BitcoinNetwork network) {
        return network == BitcoinNetwork.MAINNET || network == BitcoinNetwork.SIGNET;
    }

    public static String storageName(BitcoinNetwork network) {
        if (network == BitcoinNetwork.SIGNET) {
            return "signet";
        }
        if (network == BitcoinNetwork.MAINNET) {
            return "mainnet";
        }
        return network.name().toLowerCase(Locale.US);
    }

    public static String displayName(Context context, BitcoinNetwork network) {
        if (network == BitcoinNetwork.SIGNET) {
            return context.getString(R.string.sync_network_signet);
        }
        return context.getString(R.string.sync_network_mainnet);
    }

    public static String displayName(Context context) {
        return displayName(context, get(context));
    }
}
