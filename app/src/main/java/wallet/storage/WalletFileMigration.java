package wallet.storage;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Moves legacy wallet files from cache to persistent app storage once. */
public final class WalletFileMigration {

    private WalletFileMigration() {
    }

    public static void migrate(Context context, String walletName) {
        File oldWallet = new File(
                context.getCacheDir(), walletName + ".wallet");
        File newWallet = new File(
                context.getFilesDir(), walletName + ".wallet");

        if (newWallet.exists() || !oldWallet.exists()) {
            return;
        }

        try {
            copy(oldWallet, newWallet);

            File oldChain = new File(
                    context.getCacheDir(), walletName + ".spvchain");
            File newChain = new File(
                    context.getFilesDir(), walletName + ".spvchain");

            if (oldChain.exists() && !newChain.exists()) {
                copy(oldChain, newChain);
            }
        } catch (Exception ignored) {
            // Leave the legacy files untouched if migration fails.
        }
    }

    private static void copy(File source, File destination) throws Exception {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }
}
