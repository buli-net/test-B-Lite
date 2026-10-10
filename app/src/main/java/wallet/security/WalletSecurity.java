package wallet.security;

import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;

public final class WalletSecurity {

    private static volatile AesKey sessionKey;

    public static final int ERROR_ALREADY_ENCRYPTED = 1;
    public static final int ERROR_LOCKED = 2;
    public static final int ERROR_NO_DETERMINISTIC_SEED = 3;
    public static final int ERROR_UNSUPPORTED_ENCRYPTION = 4;

    public static final class WalletSecurityException extends IllegalStateException {
        private final int code;

        public WalletSecurityException(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    private WalletSecurity() {
    }

    public static boolean isEncrypted(Wallet wallet) {
        DeterministicSeed seed = wallet.getKeyChainSeed();
        return seed != null && seed.isEncrypted();
    }

    public static AesKey getSessionKey() {
        return sessionKey;
    }

    /**
     * Returns true only when the current session key is valid for this exact wallet.
     * A non-null session key by itself is not sufficient because the active wallet
     * may have changed since the key was established.
     */
    public static boolean isSessionValid(Wallet wallet) {
        if (wallet == null) {
            return false;
        }
        if (!isEncrypted(wallet)) {
            return true;
        }
        AesKey key = sessionKey;
        return key != null && wallet.checkAESKey(key);
    }

    /**
     * Verifies a password without installing it as the persistent session key.
     * This is used for one-shot sensitive operations such as exporting a backup
     * or revealing the recovery phrase so those actions do not silently leave
     * the wallet unlocked.
     */
    public static AesKey verifyPassword(Wallet wallet, String password) {
        if (!isEncrypted(wallet)) {
            return null;
        }

        AesKey key = deriveKey(wallet.getKeyCrypter(), password);
        return wallet.checkAESKey(key) ? key : null;
    }

    public static void clearSessionKey() {
        sessionKey = null;
    }

    public static boolean unlock(Wallet wallet, String password) {
        if (!isEncrypted(wallet)) {
            sessionKey = null;
            return true;
        }

        AesKey key = deriveKey(wallet.getKeyCrypter(), password);
        if (!wallet.checkAESKey(key)) {
            return false;
        }

        sessionKey = key;
        return true;
    }

    public static void encrypt(Wallet wallet, String password) {
        if (isEncrypted(wallet)) {
            throw new WalletSecurityException(ERROR_ALREADY_ENCRYPTED);
        }

        KeyCrypterScrypt crypter = new KeyCrypterScrypt();
        AesKey key = crypter.deriveKey(password);
        wallet.encrypt(crypter, key);
        sessionKey = key;
    }

    public static void decrypt(Wallet wallet) {
        AesKey key = sessionKey;
        if (key == null || !wallet.checkAESKey(key)) {
            throw new WalletSecurityException(ERROR_LOCKED);
        }

        wallet.decrypt(key);
        sessionKey = null;
    }

    public static DeterministicSeed getDecryptedSeed(Wallet wallet) {
        return getDecryptedSeed(wallet, sessionKey);
    }

    /**
     * Decrypts the deterministic seed using an explicitly authorized key.
     * The supplied key is never copied into the global session state.
     */
    public static DeterministicSeed getDecryptedSeed(Wallet wallet, AesKey authorizedKey) {
        DeterministicSeed seed = wallet.getKeyChainSeed();
        if (seed == null) {
            throw new WalletSecurityException(ERROR_NO_DETERMINISTIC_SEED);
        }

        if (!seed.isEncrypted()) {
            return seed;
        }

        if (authorizedKey == null || !wallet.checkAESKey(authorizedKey)) {
            throw new WalletSecurityException(ERROR_LOCKED);
        }

        KeyCrypter crypter = wallet.getKeyCrypter();
        return seed.decrypt(crypter, "", authorizedKey);
    }

    private static AesKey deriveKey(KeyCrypter crypter, String password) {
        if (!(crypter instanceof KeyCrypterScrypt)) {
            throw new WalletSecurityException(ERROR_UNSUPPORTED_ENCRYPTION);
        }
        return ((KeyCrypterScrypt) crypter).deriveKey(password);
    }
}
