/** Creates a wallet backup through the Android document provider. */

package wallet.backup;

import wallet.main.BaseActivity;

import android.view.View;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
import org.bitcoinj.crypto.AesKey;
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
import wallet.main.NetworkConfig;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.security.WalletSecurity;
import wallet.ui.TextViewUtils;

public class BackupActivity extends BaseActivity {

    private AlertDialog recoveryPhraseDialog;
    private String recoveryPhraseInMemory;
    private final Handler securityHandler = new Handler(Looper.getMainLooper());
    private Runnable clearRecoveryClipboardRunnable;

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
        Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) {
            showToast(getString(R.string.wallet_not_ready));
            return;
        }

        if (!WalletSecurity.isEncrypted(wallet) || WalletSecurity.isSessionValid(wallet)) {
            performBackup();
            return;
        }

        View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
        final EditText password = passwordView.findViewById(R.id.dialogPasswordInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_unlock_title)
                .setMessage(R.string.unlock_before_backup)
                .setView(passwordView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.create_backup, (dialog, which) ->
                        unlockAndBackup(wallet, password.getText().toString()))
                .show();
    }

    private void unlockAndBackup(Wallet wallet, String password) {
        new Thread(() -> {
            try {
                // Backup creation is an authorization check, not a request to leave the
                // wallet unlocked. bitcoinj can serialize an encrypted wallet while it
                // remains encrypted, so do not install a persistent session key here.
                if (WalletSecurity.verifyPassword(wallet, password) == null) {
                    showToast(getString(R.string.wrong_password));
                    return;
                }
                performBackup();
            } catch (Exception error) {
                showToast(getString(
                        R.string.security_operation_failed,
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage()));
            }
        }, "wallet-backup-authorize").start();
    }

    private void performBackup() {
        org.bitcoinj.wallet.Wallet wallet = MainActivityPresenter.getActiveWallet();
        org.bitcoinj.core.NetworkParameters parameters =
                MainActivityPresenter.getActiveParameters();

        if (wallet == null || parameters == null) {
            showToast(getString(R.string.wallet_not_ready));
            return;
        }

        new Thread(() -> {
            Context.propagate(Context.getOrCreate(parameters));

            try {
                File walletFile = getWalletFile();
                MainActivityPresenter.saveActiveWalletToFile(walletFile);
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
                getString(R.string.backup_title_file,
                        Constants.WALLET_NAME + "-" + NetworkConfig.storageName(NetworkConfig.get(this))));
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
        Wallet wallet = MainActivityPresenter.getActiveWallet();
        if (wallet == null) {
            showToast(getString(R.string.wallet_not_ready));
            return;
        }

        if (!WalletSecurity.isEncrypted(wallet) || WalletSecurity.isSessionValid(wallet)) {
            revealRecoveryPhrase(wallet, "");
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
                AesKey authorizationKey = WalletSecurity.getSessionKey();
                if (WalletSecurity.isEncrypted(wallet)
                        && !WalletSecurity.isSessionValid(wallet)) {
                    if (password.isEmpty()) {
                        showToast(getString(R.string.wrong_password));
                        return;
                    }
                    authorizationKey = WalletSecurity.verifyPassword(wallet, password);
                    if (authorizationKey == null) {
                        showToast(getString(R.string.wrong_password));
                        return;
                    }
                }

                DeterministicSeed seed = WalletSecurity.getDecryptedSeed(wallet, authorizationKey);
                if (seed.getMnemonicCode() == null || seed.getMnemonicCode().isEmpty()) {
                    throw new IllegalStateException(getString(R.string.recovery_phrase_unavailable));
                }

                String phrase = joinWords(seed.getMnemonicCode());
                runOnUiThread(() -> showPhraseDialog(phrase));
            } catch (Exception error) {
                if (error instanceof WalletSecurity.WalletSecurityException) {
                    int messageId;
                    switch (((WalletSecurity.WalletSecurityException) error).getCode()) {
                        case WalletSecurity.ERROR_LOCKED:
                            messageId = R.string.wallet_locked;
                            break;
                        case WalletSecurity.ERROR_NO_DETERMINISTIC_SEED:
                            messageId = R.string.recovery_phrase_unavailable;
                            break;
                        case WalletSecurity.ERROR_UNSUPPORTED_ENCRYPTION:
                            messageId = R.string.security_unsupported_wallet_encryption;
                            break;
                        default:
                            messageId = R.string.security_operation_failed;
                            showToast(getString(messageId, error.getClass().getSimpleName()));
                            return;
                    }
                    showToast(getString(messageId));
                } else {
                    showToast(getString(
                            R.string.security_operation_failed,
                            error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage()));
                }
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
        recoveryPhraseInMemory = phrase;

        recoveryPhraseDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.recovery_phrase_title)
                .setMessage(R.string.recovery_phrase_warning)
                .setView(phraseView)
                .setNeutralButton(R.string.copy_recovery_phrase, (dialog, which) -> {
                    String currentPhrase = recoveryPhraseInMemory;
                    if (currentPhrase == null || currentPhrase.isEmpty()) {
                        return;
                    }
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText(
                            getString(R.string.recovery_phrase_clip_label), currentPhrase));
                    scheduleRecoveryClipboardClear(clipboard, currentPhrase);
                    Toast.makeText(
                            this, R.string.recovery_phrase_copied, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.got_it, null)
                .create();

        recoveryPhraseDialog.setOnDismissListener(dialog -> {
            phraseView.setText("");
            recoveryPhraseInMemory = null;
            recoveryPhraseDialog = null;
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        });
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        recoveryPhraseDialog.show();
    }

    @Override
    protected void onPause() {
        if (recoveryPhraseDialog != null && recoveryPhraseDialog.isShowing()) {
            recoveryPhraseDialog.dismiss();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (clearRecoveryClipboardRunnable != null) {
            securityHandler.removeCallbacks(clearRecoveryClipboardRunnable);
            clearRecoveryClipboardRunnable = null;
        }
        recoveryPhraseInMemory = null;
        super.onDestroy();
    }

    private void scheduleRecoveryClipboardClear(ClipboardManager clipboard, String copiedPhrase) {
        if (clearRecoveryClipboardRunnable != null) {
            securityHandler.removeCallbacks(clearRecoveryClipboardRunnable);
        }

        clearRecoveryClipboardRunnable = () -> {
            try {
                ClipData current = clipboard.getPrimaryClip();
                if (current != null
                        && current.getItemCount() > 0
                        && copiedPhrase.contentEquals(current.getItemAt(0).coerceToText(this))) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        clipboard.clearPrimaryClip();
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                    }
                }
            } finally {
                clearRecoveryClipboardRunnable = null;
            }
        };
        securityHandler.postDelayed(clearRecoveryClipboardRunnable, 60_000L);
    }

    private void createSafetyCopy(File walletFile) throws java.io.IOException {
        File parent = walletFile.getParentFile();
        File dir = new File(parent == null ? getFilesDir() : parent, "backup-safety");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException(getString(R.string.backup_safety_directory_failed));
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
        File active = MainActivityPresenter.getActiveWalletFile();
        return active != null
                ? active
                : new File(
                        NetworkConfig.walletDirectory(this, NetworkConfig.get(this)),
                        Constants.WALLET_NAME + ".wallet");
    }

    private void showToast(String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
