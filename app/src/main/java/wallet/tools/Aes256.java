package wallet.tools;

import java.util.Arrays;
final class Aes256 {
    private final javax.crypto.Cipher cipher;
    Aes256(byte[] key, int offset) {
        try {
            cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(Arrays.copyOfRange(key, offset, offset + 32), "AES"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
    byte[] encrypt(byte[] block) {
        try { return cipher.doFinal(block); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
