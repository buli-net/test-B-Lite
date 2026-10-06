package wallet.tools;

import org.bitcoinj.crypto.ECKey;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
final class Bip38Encoder {
    private static final int N = 16384;
    private static final int R = 8;
    private static final int P = 8;

    private Bip38Encoder() { }

    static String encrypt(ECKey key, String address, String passphrase) {
        try {
            byte[] salt = Arrays.copyOf(Hashing.doubleSha256(address.getBytes(StandardCharsets.US_ASCII)), 4);
            String normalized = Normalizer.normalize(passphrase, Normalizer.Form.NFC);
            byte[] derived = Scrypt.scrypt(normalized.getBytes(StandardCharsets.UTF_8), salt, N, R, P, 64);
            byte[] secret = key.getPrivKeyBytes();
            byte[] block1 = new byte[16];
            byte[] block2 = new byte[16];
            for (int i = 0; i < 16; i++) {
                block1[i] = (byte) (secret[i] ^ derived[i]);
                block2[i] = (byte) (secret[i + 16] ^ derived[i + 16]);
            }
            Aes256 aes = new Aes256(derived, 32);
            block1 = aes.encrypt(block1);
            block2 = aes.encrypt(block2);
            byte[] payload = new byte[39];
            payload[0] = 0x01;
            payload[1] = 0x42;
            payload[2] = (byte) 0xE0; // compressed + no EC multiply
            System.arraycopy(salt, 0, payload, 3, 4);
            System.arraycopy(block1, 0, payload, 7, 16);
            System.arraycopy(block2, 0, payload, 23, 16);
            return Base58Check.encode(payload);
        } finally {
            // The passphrase is not persisted; callers only retain the encrypted result.
        }
    }
}
