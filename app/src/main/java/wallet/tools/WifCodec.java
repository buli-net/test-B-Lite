package wallet.tools;

import wallet.Constants;
import java.util.Arrays;
final class WifCodec {
    private WifCodec() { }
    static String privateKeyHex(String wif) {
        byte[] payload = Base58Check.decode(wif);
        if (payload.length != 33 && payload.length != 34) throw new IllegalArgumentException("Invalid WIF");
        if (payload[0] != (Constants.IS_PRODUCTION ? (byte) 0x80 : (byte) 0xEF)) throw new IllegalArgumentException("Wrong network WIF");
        return toHex(Arrays.copyOfRange(payload, 1, 33));
    }
    static String toHex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) out.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        return out.toString();
    }
}
