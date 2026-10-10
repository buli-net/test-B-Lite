/** Selects and restores a wallet backup. */

package wallet.backup;

import wallet.main.BaseActivity;

import android.net.Uri;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import android.view.View;

import org.bitcoinj.crypto.AesKey;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;

import wallet.security.WalletSecurity;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

public class RestoreActivity extends BaseActivity {

    private final ActivityResultLauncher<String[]> backupFileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    confirmRestore(uri);
                }
            });


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_restore);

        Toolbar toolbar = findViewById(R.id.toolbar_restore);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.restore_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> finish());

        Button chooseButton = findViewById(R.id.btnChooseRestore);
        chooseButton.setOnClickListener(v -> chooseBackup());

        EditText mnemonicInput = findViewById(R.id.etRecoveryPhrase);
        EditText birthdayInput = findViewById(R.id.etWalletBirthday);
        Button mnemonicButton = findViewById(R.id.btnRestoreMnemonic);
        mnemonicButton.setOnClickListener(v ->
                restoreFromMnemonic(mnemonicInput, birthdayInput));
    }

    private void chooseBackup() {
        backupFileLauncher.launch(new String[]{"application/octet-stream", "application/zip", "*/*"});
    }

    private void restoreFromMnemonic(
            EditText mnemonicInput,
            EditText birthdayInput) {
        String mnemonic = mnemonicInput.getText().toString().trim();
        String birthday = birthdayInput.getText().toString().trim();

        if (mnemonic.isEmpty()) {
            Toast.makeText(this, R.string.recovery_phrase_required, Toast.LENGTH_LONG).show();
            return;
        }

        if (!birthday.isEmpty() && !birthday.matches("\\d{4}-\\d{2}-\\d{2}")) {
            Toast.makeText(this, R.string.invalid_wallet_birthday, Toast.LENGTH_LONG).show();
            return;
        }

        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            Toast.makeText(this, R.string.wallet_core_not_ready, Toast.LENGTH_LONG).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.mnemonic_restore_question)
                .setMessage(R.string.mnemonic_restore_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.restore_action, (dialog, which) -> {
                    presenter.restoreWalletFromMnemonic(mnemonic, birthday);
                    Toast.makeText(
                            this,
                            R.string.mnemonic_restore_in_progress,
                            Toast.LENGTH_LONG).show();
                    finish();
                })
                .show();
    }

    private void confirmRestore(Uri backupUri) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_question)
                .setMessage(R.string.restore_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.restore_action, (dialog, which) ->
                        authorizeCurrentWalletForRestore(backupUri))
                .show();
    }

    private void authorizeCurrentWalletForRestore(Uri backupUri) {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        Wallet wallet = MainActivityPresenter.getActiveWallet();

        if (presenter == null || wallet == null) {
            Toast.makeText(
                    this,
                    R.string.wallet_core_not_ready,
                    Toast.LENGTH_LONG).show();
            return;
        }

        if (!WalletSecurity.isEncrypted(wallet)) {
            startRestore(presenter, backupUri, null);
            return;
        }

        View passwordView = getLayoutInflater().inflate(R.layout.dialog_password, null);
        final EditText password = passwordView.findViewById(R.id.dialogPasswordInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_unlock_title)
                .setMessage(R.string.restore_unlock_message)
                .setView(passwordView)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.restore_action, (dialog, which) -> {
                    String enteredPassword = password.getText().toString();
                    new Thread(() -> {
                        AesKey authorizationKey = WalletSecurity.verifyPassword(
                                wallet, enteredPassword);
                        runOnUiThread(() -> {
                            if (authorizationKey == null) {
                                Toast.makeText(
                                        this,
                                        R.string.wrong_password,
                                        Toast.LENGTH_LONG).show();
                                return;
                            }
                            startRestore(presenter, backupUri, authorizationKey);
                        });
                    }, "wallet-restore-authorize").start();
                })
                .show();
    }

    private void startRestore(
            MainActivityPresenter presenter,
            Uri backupUri,
            AesKey authorizationKey) {
        presenter.restoreWallet(backupUri, authorizationKey);
        Toast.makeText(
                this,
                R.string.restore_in_progress,
                Toast.LENGTH_LONG).show();
        finish();
    }
}
