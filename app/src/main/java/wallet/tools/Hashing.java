package wallet.tools;

final class Hashing {
    private Hashing() { }
    static byte[] doubleSha256(byte[] input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(digest.digest(input));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
