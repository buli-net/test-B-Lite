package wallet.tools;

import java.util.Arrays;
final class Scrypt {
    private Scrypt() { }

    static byte[] scrypt(byte[] password, byte[] salt, int n, int r, int p, int dkLen) {
        if (n <= 1 || (n & (n - 1)) != 0) throw new IllegalArgumentException("Invalid scrypt N");
        int blockSize = 128 * r;
        byte[] b = pbkdf2(password, salt, p * blockSize);
        byte[] xy = new byte[256 * r];
        byte[] v = new byte[128 * r * n];
        for (int i = 0; i < p; i++) romix(b, i * blockSize, r, n, v, xy);
        return pbkdf2(password, b, dkLen);
    }

    private static byte[] pbkdf2(byte[] password, byte[] salt, int length) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(password, "HmacSHA256"));
            byte[] out = new byte[length];
            int blocks = (length + 31) / 32;
            byte[] input = new byte[salt.length + 4];
            System.arraycopy(salt, 0, input, 0, salt.length);
            int pos = 0;
            for (int blockIndex = 1; blockIndex <= blocks; blockIndex++) {
                input[salt.length] = (byte) (blockIndex >>> 24);
                input[salt.length + 1] = (byte) (blockIndex >>> 16);
                input[salt.length + 2] = (byte) (blockIndex >>> 8);
                input[salt.length + 3] = (byte) blockIndex;

                byte[] u = mac.doFinal(input);
                byte[] t = Arrays.copyOf(u, u.length);
                // Scrypt uses PBKDF2 with one HMAC iteration for its initial and final step.
                int copy = Math.min(32, length - pos);
                System.arraycopy(t, 0, out, pos, copy);
                pos += copy;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void romix(byte[] b, int offset, int r, int n, byte[] v, byte[] xy) {
        int block = 128 * r;
        byte[] x = new byte[block];
        System.arraycopy(b, offset, x, 0, block);
        for (int i = 0; i < n; i++) {
            System.arraycopy(x, 0, v, i * block, block);
            blockMix(x, xy, r);
        }
        for (int i = 0; i < n; i++) {
            int j = integerify(x, r) & (n - 1);
            for (int k = 0; k < block; k++) x[k] ^= v[j * block + k];
            blockMix(x, xy, r);
        }
        System.arraycopy(x, 0, b, offset, block);
    }

    private static int integerify(byte[] x, int r) {
        int index = (2 * r - 1) * 64;
        return (x[index] & 0xff) | ((x[index + 1] & 0xff) << 8) | ((x[index + 2] & 0xff) << 16) | ((x[index + 3] & 0xff) << 24);
    }

    private static void blockMix(byte[] b, byte[] y, int r) {
        byte[] x = new byte[64];
        System.arraycopy(b, (2 * r - 1) * 64, x, 0, 64);
        for (int i = 0; i < 2 * r; i++) {
            for (int j = 0; j < 64; j++) x[j] ^= b[i * 64 + j];
            salsa208(x);
            System.arraycopy(x, 0, y, i * 64, 64);
        }
        for (int i = 0; i < r; i++) System.arraycopy(y, (2 * i) * 64, b, i * 64, 64);
        for (int i = 0; i < r; i++) System.arraycopy(y, (2 * i + 1) * 64, b, (i + r) * 64, 64);
    }

    private static void salsa208(byte[] b) {
        int[] x = new int[16];
        for (int i = 0; i < 16; i++) x[i] = le(b, i * 4);
        int[] z = Arrays.copyOf(x, 16);
        for (int i = 0; i < 8; i += 2) {
            z[4] ^= Integer.rotateLeft(z[0] + z[12], 7);
            z[8] ^= Integer.rotateLeft(z[4] + z[0], 9);
            z[12] ^= Integer.rotateLeft(z[8] + z[4], 13);
            z[0] ^= Integer.rotateLeft(z[12] + z[8], 18);
            z[9] ^= Integer.rotateLeft(z[5] + z[1], 7);
            z[13] ^= Integer.rotateLeft(z[9] + z[5], 9);
            z[1] ^= Integer.rotateLeft(z[13] + z[9], 13);
            z[5] ^= Integer.rotateLeft(z[1] + z[13], 18);
            z[14] ^= Integer.rotateLeft(z[10] + z[6], 7);
            z[2] ^= Integer.rotateLeft(z[14] + z[10], 9);
            z[6] ^= Integer.rotateLeft(z[2] + z[14], 13);
            z[10] ^= Integer.rotateLeft(z[6] + z[2], 18);
            z[3] ^= Integer.rotateLeft(z[15] + z[11], 7);
            z[7] ^= Integer.rotateLeft(z[3] + z[15], 9);
            z[11] ^= Integer.rotateLeft(z[7] + z[3], 13);
            z[15] ^= Integer.rotateLeft(z[11] + z[7], 18);
            z[1] ^= Integer.rotateLeft(z[0] + z[3], 7);
            z[2] ^= Integer.rotateLeft(z[1] + z[0], 9);
            z[3] ^= Integer.rotateLeft(z[2] + z[1], 13);
            z[0] ^= Integer.rotateLeft(z[3] + z[2], 18);
            z[6] ^= Integer.rotateLeft(z[5] + z[4], 7);
            z[7] ^= Integer.rotateLeft(z[6] + z[5], 9);
            z[4] ^= Integer.rotateLeft(z[7] + z[6], 13);
            z[5] ^= Integer.rotateLeft(z[4] + z[7], 18);
            z[11] ^= Integer.rotateLeft(z[10] + z[9], 7);
            z[8] ^= Integer.rotateLeft(z[11] + z[10], 9);
            z[9] ^= Integer.rotateLeft(z[8] + z[11], 13);
            z[10] ^= Integer.rotateLeft(z[9] + z[8], 18);
            z[12] ^= Integer.rotateLeft(z[15] + z[14], 7);
            z[13] ^= Integer.rotateLeft(z[12] + z[15], 9);
            z[14] ^= Integer.rotateLeft(z[13] + z[12], 13);
            z[15] ^= Integer.rotateLeft(z[14] + z[13], 18);
        }
        for (int i = 0; i < 16; i++) putLe(b, i * 4, z[i] + x[i]);
    }
    private static int le(byte[] b, int i) {
        return (b[i] & 255)
                | ((b[i + 1] & 255) << 8)
                | ((b[i + 2] & 255) << 16)
                | ((b[i + 3] & 255) << 24);
    }

    private static void putLe(byte[] b, int i, int v) {
        b[i] = (byte) v;
        b[i + 1] = (byte) (v >>> 8);
        b[i + 2] = (byte) (v >>> 16);
        b[i + 3] = (byte) (v >>> 24);
    }
}
