/** Creates a wallet backup through the Android document provider. */

package wallet.backup;

import wallet.main.BaseActivity;

import android.view.View;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AlertDialog;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.core.Context;
import org.bitcoinj.wallet.DeterministicSeed;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.kits.WalletAppKit;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import wallet.Constants;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.security.WalletSecurity;
import wallet.ui.TextViewUtils;

public class BackupActivity extends BaseActivity {

    private final ActivityResultLauncher<String> backupFileLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/octet-stream"), uri -> {
                if (uri != null) {
                    copyBackup(uri);
                }
            });


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_backup);

        Toolbar toolbar = findViewById(R.id.toolbar_backup);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.backup_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> finish());

        Button createButton = findViewById(R.id.btnCreateBackup);
        createButton.setOnClickListener(v -> prepareBackup());

        Button recoveryButton = findViewById(R.id.btnShowRecoveryPhrase);
        recoveryButton.setOnClickListener(v -> showRecoveryPhrase());

        TextView pathView = findViewById(R.id.tvBackupPath);
        TextViewUtils.configureSelectableMiddleEllipsis(pathView);
        TextViewUtils.setTextIfChanged(pathView, getWalletFile().getAbsolutePath());
    }

    private void prepareBackup() {
        WalletAppKit walletAppKit = MainActivityPresenter.getActiveWalletAppKit();
        org.bitcoinj.core.NetworkParameters parameters =
                MainActivityPresenter.getActiveParameters();

        if (walletAppKit == null || parameters == null) {
            showToast(getString(R.string.wallet_not_ready));
            return;
        }

        new Thread(() -> {
            Context.propagate(Context.getOrCreate(parameters));

            try {
                File walletFile = getWalletFile();
                walletAppKit.wallet().saveToFile(walletFile);
                createSafetyCopy(walletFile);

                if (!walletFile.exists() || walletFile.length() == 0) {
                    throw new java.io.IOException(getString(R.string.wallet_file_empty));
                }

                runOnUiThread(() -> openCreateDocument());
            } catch (Exception error) {
                showToast(getString(R.string.backup_failed, error.getMessage()));
            }
        }, "wallet-backup").start();
    }

    private void openCreateDocument() {
        backupFileLauncher.launch(
                getString(R.string.backup_title_file, Constants.WALLET_NAME));
    }

    private void copyBackup(Uri destination) {
        new Thread(() -> {
            try (InputStream input = new FileInputStream(getWalletFile());
                 OutputStream output = getContentResolver().openOutputStream(destination)) {

                if (output == null) {
                    throw new java.io.IOException(
                            getString(R.string.backup_destination_open_failed));
                }

                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();

                showToast(getString(R.string.backup_success));
            } catch (Exception error) {
                showToast(getString(R.string.backup_failed, error.getMessage()));
            }
        }, "wallet-backup-copy").start();
    }

    private void showRecoveryPhrase() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        Wallet wallet = kit == null ? null : kit.wallet();
        if (wallet == null) {
            showToast(getString(R.string.wallet_not_ready));
            return;
        }

        View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
        final EditText password = passwordView.findViewById(R.id.dialogPasswordInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.recovery_phrase_unlock_title)
                .setMessage(R.string.unlock_before_recovery_phrase)
                .setView(passwordView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.show_recovery_phrase, (dialog, which) ->
                        revealRecoveryPhrase(wallet, password.getText().toString()))
                .show();
    }

    private void revealRecoveryPhrase(Wallet wallet, String password) {
        new Thread(() -> {
            try {
                if (WalletSecurity.isEncrypted(wallet)
                        && WalletSecurity.getSessionKey() == null
                        && (password.isEmpty() || !WalletSecurity.unlock(wallet, password))) {
                    showToast(getString(R.string.wrong_password));
                    return;
                }

                DeterministicSeed seed = WalletSecurity.getDecryptedSeed(wallet);
                if (seed.getMnemonicCode() == null || seed.getMnemonicCode().isEmpty()) {
                    throw new IllegalStateException("Recovery phrase is unavailable.");
                }

                String phrase = joinWords(seed.getMnemonicCode());
                runOnUiThread(() -> showPhraseDialog(phrase));
            } catch (Exception error) {
                showToast(getString(
                        R.string.security_operation_failed,
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage()));
            }
        }, "wallet-recovery-phrase").start();
    }

    private String joinWords(java.util.List<String> words) {
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (result.length() > 0) result.append(' ');
            result.append(word);
        }
        return result.toString();
    }

    private void showPhraseDialog(String phrase) {
        TextView phraseView = (TextView) getLayoutInflater().inflate(
                R.layout.dialog_recovery_phrase, null);
        phraseView.setText(phrase);

        new AlertDialog.Builder(this)
                .setTitle(R.string.recovery_phrase_title)
                .setMessage(R.string.recovery_phrase_warning)
                .setView(phraseView)
                .setNeutralButton(R.string.copy_recovery_phrase, (dialog, which) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                            getString(R.string.recovery_phrase_clip_label), phrase));
                    Toast.makeText(this, R.string.recovery_phrase_copied, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.got_it, null)
                .show();
    }

    private void createSafetyCopy(File walletFile) throws java.io.IOException {
        File dir = new File(getFilesDir(), "backup-safety");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("Unable to create backup safety directory");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        File target = new File(dir, Constants.WALLET_NAME + "-" + stamp + ".wallet");
        try (InputStream input = new FileInputStream(walletFile);
             OutputStream output = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        File[] files = dir.listFiles();
        if (files != null && files.length > 5) {
            java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < files.length - 5; i++) {
                files[i].delete();
            }
        }
    }

    private File getWalletFile() {
        return new File(
                getFilesDir(),
                Constants.WALLET_NAME + ".wallet");
    }

    private void showToast(String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
