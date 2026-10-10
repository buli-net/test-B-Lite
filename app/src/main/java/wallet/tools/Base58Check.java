package wallet.tools;

import android.text.TextUtils;
import java.util.Arrays;
final class Base58Check {
    private static final char[] ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private Base58Check() { }
    static String encode(byte[] payload) {
        byte[] data = Arrays.copyOf(payload, payload.length + 4);
        byte[] checksum = Hashing.doubleSha256(payload);
        System.arraycopy(checksum, 0, data, payload.length, 4);
        return encodeRaw(data);
    }
    static byte[] decode(String value) {
        if (TextUtils.isEmpty(value)) throw new IllegalArgumentException("Empty Base58 value");
        byte[] decoded = decodeRaw(value);
        if (decoded.length < 5) throw new IllegalArgumentException("Invalid Base58Check value");
        byte[] payload = Arrays.copyOf(decoded, decoded.length - 4);
        byte[] checksum = Arrays.copyOfRange(decoded, decoded.length - 4, decoded.length);
        byte[] expected = Hashing.doubleSha256(payload);
        if (!Arrays.equals(checksum, Arrays.copyOf(expected, 4))) throw new IllegalArgumentException("Invalid Base58Check checksum");
        return payload;
    }
    static String decodePayloadHex(String value) {
        return WifCodec.toHex(decode(value));
    }
    static String addressHashHex(String address) {
        byte[] payload = decode(address);
        if (payload.length != 21) throw new IllegalArgumentException("Unsupported Bitcoin address format");
        return WifCodec.toHex(Arrays.copyOfRange(payload, 1, payload.length));
    }
    private static byte[] decodeRaw(String input) {
        java.math.BigInteger value = java.math.BigInteger.ZERO;
        java.math.BigInteger base = java.math.BigInteger.valueOf(58);
        for (int i = 0; i < input.length(); i++) {
            int index = new String(ALPHABET).indexOf(input.charAt(i));
            if (index < 0) throw new IllegalArgumentException("Invalid Base58 character");
            value = value.multiply(base).add(java.math.BigInteger.valueOf(index));
        }
        byte[] raw = value.toByteArray();
        if (raw.length > 0 && raw[0] == 0) raw = Arrays.copyOfRange(raw, 1, raw.length);
        int leading = 0;
        while (leading < input.length() && input.charAt(leading) == '1') leading++;
        byte[] out = new byte[leading + raw.length];
        System.arraycopy(raw, 0, out, leading, raw.length);
        return out;
    }
    private static String encodeRaw(byte[] input) {
        java.math.BigInteger value = new java.math.BigInteger(1, input);
        StringBuilder result = new StringBuilder();
        java.math.BigInteger base = java.math.BigInteger.valueOf(58);
        while (value.signum() > 0) {
            java.math.BigInteger[] div = value.divideAndRemainder(base);
            result.append(ALPHABET[div[1].intValue()]);
            value = div[0];
        }
        for (byte b : input) {
            if (b == 0) result.append('1'); else break;
        }
        return result.reverse().toString();
    }
}
