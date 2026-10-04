package wallet.security;

import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;

public final class WalletSecurity {

    private static volatile AesKey sessionKey;

    private WalletSecurity() {
    }

    public static boolean isEncrypted(Wallet wallet) {
        DeterministicSeed seed = wallet.getKeyChainSeed();
        return seed != null && seed.isEncrypted();
    }

    public static AesKey getSessionKey() {
        return sessionKey;
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
            throw new IllegalStateException("Wallet is already encrypted.");
        }

        KeyCrypterScrypt crypter = new KeyCrypterScrypt();
        AesKey key = crypter.deriveKey(password);
        wallet.encrypt(crypter, key);
        sessionKey = key;
    }

    public static void decrypt(Wallet wallet) {
        AesKey key = sessionKey;
        if (key == null || !wallet.checkAESKey(key)) {
            throw new IllegalStateException("Wallet is locked.");
        }

        wallet.decrypt(key);
        sessionKey = null;
    }

    public static DeterministicSeed getDecryptedSeed(Wallet wallet) {
        DeterministicSeed seed = wallet.getKeyChainSeed();
        if (seed == null) {
            throw new IllegalStateException("Wallet has no deterministic seed.");
        }

        if (!seed.isEncrypted()) {
            return seed;
        }

        AesKey key = sessionKey;
        if (key == null || !wallet.checkAESKey(key)) {
            throw new IllegalStateException("Wallet is locked.");
        }

        KeyCrypter crypter = wallet.getKeyCrypter();
        return seed.decrypt(crypter, "", key);
    }

    private static AesKey deriveKey(KeyCrypter crypter, String password) {
        if (!(crypter instanceof KeyCrypterScrypt)) {
            throw new IllegalStateException("Unsupported wallet encryption.");
        }
        return ((KeyCrypterScrypt) crypter).deriveKey(password);
    }
}
