package wallet.tools;

import org.bitcoinj.core.NetworkParameters;
import java.util.Arrays;

final class WifCodec {
    private WifCodec() { }

    static String privateKeyHex(String wif, NetworkParameters parameters) {
        byte[] payload = Base58Check.decode(wif);
        if (payload.length != 33 && payload.length != 34) {
            throw new IllegalArgumentException("Invalid WIF");
        }
        if (parameters == null || (payload[0] & 0xff) != parameters.getDumpedPrivateKeyHeader()) {
            throw new IllegalArgumentException("Wrong network WIF");
        }
        return toHex(Arrays.copyOfRange(payload, 1, 33));
    }

    static String toHex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
