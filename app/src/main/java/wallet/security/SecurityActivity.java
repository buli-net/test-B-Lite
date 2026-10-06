package wallet.security;

import wallet.main.BaseActivity;

import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;

import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.kits.WalletAppKit;

import wallet.main.MainActivityPresenter;
import wallet.main.R;

public class SecurityActivity extends BaseActivity {

    private EditText passwordInput;
    private TextView statusView;
    private Button setPasswordButton;
    private Button unlockButton;
    private Button lockButton;
    private Button removePasswordButton;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRefreshTask = new Runnable() {
        @Override
        public void run() {
            if (!isFinishing() && !isDestroyed()) {
                refreshStatus();
                statusHandler.postDelayed(this, 1000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_security);

        Toolbar toolbar = findViewById(R.id.toolbar_security);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.security_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        passwordInput = findViewById(R.id.etSecurityPassword);
        passwordInput.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        statusView = findViewById(R.id.tvSecurityStatus);
        setPasswordButton = findViewById(R.id.btnSetPassword);
        unlockButton = findViewById(R.id.btnUnlockWallet);
        lockButton = findViewById(R.id.btnLockWallet);
        removePasswordButton = findViewById(R.id.btnRemovePassword);

        setPasswordButton.setOnClickListener(v -> setPassword());
        unlockButton.setOnClickListener(v -> unlockWallet());
        lockButton.setOnClickListener(v -> lockWallet());
        removePasswordButton.setOnClickListener(v -> removePassword());

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        statusHandler.removeCallbacks(statusRefreshTask);
        statusHandler.post(statusRefreshTask);
    }

    @Override
    protected void onPause() {
        statusHandler.removeCallbacks(statusRefreshTask);
        super.onPause();
    }

    private void refreshStatus() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            statusView.setText(R.string.wallet_not_ready);
            passwordInput.setEnabled(false);
            setPasswordButton.setVisibility(View.GONE);
            unlockButton.setVisibility(View.GONE);
            lockButton.setVisibility(View.GONE);
            removePasswordButton.setVisibility(View.GONE);
            setPasswordButton.setEnabled(false);
            unlockButton.setEnabled(false);
            lockButton.setEnabled(false);
            removePasswordButton.setEnabled(false);
            return;
        }

        boolean encrypted = WalletSecurity.isEncrypted(wallet);
        boolean unlocked = WalletSecurity.isSessionValid(wallet);
        statusView.setText(
                encrypted
                        ? (unlocked
                        ? R.string.wallet_status_unlocked
                        : R.string.wallet_status_locked)
                        : R.string.wallet_status_unencrypted);

        setPasswordButton.setVisibility(encrypted ? View.GONE : View.VISIBLE);
        unlockButton.setVisibility(encrypted && !unlocked ? View.VISIBLE : View.GONE);
        lockButton.setVisibility(encrypted && unlocked ? View.VISIBLE : View.GONE);
        removePasswordButton.setVisibility(encrypted && unlocked ? View.VISIBLE : View.GONE);
        passwordInput.setEnabled(true);
        setPasswordButton.setEnabled(true);
        unlockButton.setEnabled(true);
        lockButton.setEnabled(true);
        removePasswordButton.setEnabled(true);
    }

    private Wallet getWallet() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        return kit == null ? null : kit.wallet();
    }

    private String password() {
        return passwordInput.getText().toString();
    }

    private boolean validPassword(String password) {
        if (password.length() < 8) {
            Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    private void setPassword() {
        String password = password();
        if (!validPassword(password)) {
            return;
        }

        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                WalletSecurity.encrypt(wallet, password);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) {
                    presenter.saveWalletNow();
                }
                runOnUiThread(() -> {
                    passwordInput.setText("");
                    setBusy(false);
                    refreshStatus();
                    Toast.makeText(this, R.string.password_set_success, Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                WalletSecurity.clearSessionKey();
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-encrypt").start();
    }

    private void unlockWallet() {
        String password = password();
        if (password.isEmpty()) {
            showMessage(R.string.password_required);
            return;
        }

        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        setBusy(true);
        new Thread(() -> {
            try {
                boolean unlocked = WalletSecurity.unlock(wallet, password);
                runOnUiThread(() -> {
                    setBusy(false);
                    if (!unlocked) {
                        Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_LONG).show();
                        return;
                    }
                    passwordInput.setText("");
                    refreshStatus();
                    Toast.makeText(this, R.string.wallet_unlocked, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(error);
                });
            }
        }, "wallet-unlock").start();
    }

    private void lockWallet() {
        WalletSecurity.clearSessionKey();
        passwordInput.setText("");
        refreshStatus();
        Toast.makeText(this, R.string.wallet_locked_success, Toast.LENGTH_SHORT).show();
    }

    private void removePassword() {
        Wallet wallet = getWallet();
        if (wallet == null) {
            showMessage(R.string.wallet_not_ready);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.remove_password_title)
                .setMessage(R.string.remove_password_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.remove_password_action, (dialog, which) -> {
                    setBusy(true);
                    new Thread(() -> {
                        try {
                            WalletSecurity.decrypt(wallet);
                            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                            if (presenter != null) {
                                presenter.saveWalletNow();
                            }
                            runOnUiThread(() -> {
                                setBusy(false);
                                refreshStatus();
                                Toast.makeText(
                                        this,
                                        R.string.password_removed,
                                        Toast.LENGTH_LONG).show();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                setBusy(false);
                                showError(error);
                            });
                        }
                    }, "wallet-decrypt").start();
                })
                .show();
    }

    private void setBusy(boolean busy) {
        passwordInput.setEnabled(!busy);
        setPasswordButton.setEnabled(!busy);
        unlockButton.setEnabled(!busy);
        lockButton.setEnabled(!busy);
        removePasswordButton.setEnabled(!busy);
    }

    private void showMessage(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
    }

    private void showError(Exception error) {
        if (error instanceof WalletSecurity.WalletSecurityException) {
            int messageId;
            switch (((WalletSecurity.WalletSecurityException) error).getCode()) {
                case WalletSecurity.ERROR_ALREADY_ENCRYPTED:
                    messageId = R.string.wallet_already_encrypted;
                    break;
                case WalletSecurity.ERROR_LOCKED:
                    messageId = R.string.wallet_locked;
                    break;
                case WalletSecurity.ERROR_UNSUPPORTED_ENCRYPTION:
                    messageId = R.string.security_unsupported_wallet_encryption;
                    break;
                case WalletSecurity.ERROR_NO_DETERMINISTIC_SEED:
                    messageId = R.string.recovery_phrase_unavailable;
                    break;
                default:
                    messageId = R.string.security_operation_failed;
                    Toast.makeText(this, getString(messageId, error.getClass().getSimpleName()), Toast.LENGTH_LONG).show();
                    return;
            }
            Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(
                this,
                getString(
                        R.string.security_operation_failed,
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage()),
                Toast.LENGTH_LONG).show();
    }
}
