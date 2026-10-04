package wallet.tools;

import wallet.main.BaseActivity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.appcompat.widget.Toolbar;
import android.widget.TextView;
import android.widget.Toast;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.base.Coin;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.WalletChangeEventListener;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.View;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import androidx.activity.result.ActivityResultLauncher;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import androidx.appcompat.app.AlertDialog;
import org.bitcoinj.base.BitcoinNetwork;
import wallet.main.WalletSelection;
import wallet.main.ImportedWalletStore;
import wallet.main.WalletAddressType;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.BIP38PrivateKey;
import org.bitcoinj.crypto.ECKey;
import java.util.Collections;
import wallet.Constants;
import wallet.security.WalletSecurity;
import wallet.ui.TextViewUtils;

public final class WalletImportWifActivity extends BaseActivity {
    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (!TextUtils.isEmpty(result.getContents())) {
                    EditText input = findViewById(R.id.privateKeyInput);
                    input.setText(result.getContents().trim());
                    input.setSelection(input.length());
                }
            });

    private TextView importedWalletSummary;
    private android.widget.RadioGroup addressTypeGroup;
    private String selectedAddressType = WalletAddressType.P2PKH;
    private Wallet importedObservedWallet;
    private WalletChangeEventListener importedWalletChangeListener;
    private final Handler importedRefreshHandler = new Handler(Looper.getMainLooper());
    private boolean importedRefreshQueued;
    private static final long IMPORTED_REFRESH_DEBOUNCE_MS = 500L;
    private final Object importedRefreshLock = new Object();
    private long importedRefreshGeneration;
    private boolean importedRefreshRunning;
    private boolean importedRefreshPending;
    private final java.util.Map<String, View> importedWalletRows = new java.util.HashMap<>();

    private static final class ImportedBalance {
        private long confirmed;
        private long pending;

        private long total() {
            return confirmed + pending;
        }
    }

    private Wallet getWallet() {
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        return kit == null ? null : kit.wallet();
    }

    private NetworkParameters getParameters() {
        return MainActivityPresenter.getActiveParameters();
    }
    private void show(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_wallet_import_wif);
        Toolbar toolbar = findViewById(R.id.toolbar_wallet_import_wif); setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) { getSupportActionBar().setTitle(R.string.wallet_import_wif_title); getSupportActionBar().setDisplayHomeAsUpEnabled(true); }
        toolbar.setNavigationOnClickListener(v -> finish());
        EditText input = findViewById(R.id.privateKeyInput); Button importButton = findViewById(R.id.importPrivateKeyButton);
        Button scanWifQrButton = findViewById(R.id.scanWifQrButton);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        importButton.setOnClickListener(v -> importPrivateKey(input, importButton));
        scanWifQrButton.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions()
                    .setPrompt(getString(R.string.scan_wif_qr));
            barcodeLauncher.launch(options);
        });
        findViewById(R.id.wifInfoButton).setOnClickListener(v -> showWifInfo());
        addressTypeGroup = findViewById(R.id.walletAddressTypeGroup);
        addressTypeGroup.setOnCheckedChangeListener((group, checkedId) -> selectedAddressType = addressTypeFromCheckedId(checkedId));

        importedWalletSummary = findViewById(R.id.importedWalletSummary);
        renderImportedWallets();
        attachImportedWalletListener();
        refreshImportedWalletSummary();
    }

    @Override protected void onResume() {
        super.onResume();
        renderImportedWallets();
        attachImportedWalletListener();
        refreshImportedWalletSummary();
    }

    @Override protected void onPause() {
        detachImportedWalletListener();
        importedRefreshHandler.removeCallbacksAndMessages(null);
        importedRefreshQueued = false;
        synchronized (importedRefreshLock) {
            importedRefreshGeneration++;
            importedRefreshPending = false;
        }
        super.onPause();
    }

    private void importPrivateKey(EditText privateKeyInput, Button importPrivateKeyButton) {
        String encoded = privateKeyInput.getText().toString().trim();
        if (TextUtils.isEmpty(encoded)) {
            show(R.string.private_key_required);
            return;
        }

        // BIP38 encrypted private keys use the 6P prefix. They cannot be
        // imported as ordinary WIF until the user's passphrase decrypts them.
        if (isBip38(encoded)) {
            promptForBip38Passphrase(privateKeyInput, importPrivateKeyButton, encoded);
            return;
        }

        final NetworkParameters parameters = getParameters();
        importPrivateKeyButton.setEnabled(false);
        new Thread(() -> {
            try {
                ECKey key = DumpedPrivateKey.fromBase58(
                        Constants.IS_PRODUCTION ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET,
                        encoded).getKey();
                importDecodedPrivateKey(key, parameters, privateKeyInput, importPrivateKeyButton, null);
            } catch (Exception error) {
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    Toast.makeText(this, getString(
                            R.string.private_key_import_failed,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "wallet-import-key").start();
    }

    private boolean isBip38(String value) {
        return value.length() == 58 && value.startsWith("6P");
    }

    private void promptForBip38Passphrase(EditText privateKeyInput,
                                           Button importPrivateKeyButton,
                                           String encryptedKey) {
        EditText passphraseInput = new EditText(this);
        passphraseInput.setSingleLine(true);
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passphraseInput.setHint(R.string.bip38_passphrase_hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.bip38_passphrase_title)
                .setMessage(R.string.bip38_passphrase_message)
                .setView(passphraseInput)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.bip38_unlock, null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String passphrase = passphraseInput.getText().toString();
            if (passphrase.isEmpty()) {
                Toast.makeText(this, R.string.bip38_passphrase_required, Toast.LENGTH_LONG).show();
                return;
            }

            importPrivateKeyButton.setEnabled(false);
            dialog.dismiss();

            final NetworkParameters parameters = getParameters();
            new Thread(() -> {
                try {
                    BIP38PrivateKey bip38 = BIP38PrivateKey.fromBase58(
                            Constants.IS_PRODUCTION ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET,
                            encryptedKey);
                    ECKey key = bip38.decrypt(passphrase);
                    // Do not retain the passphrase. It is only used during this
                    // decryption operation and is never written to preferences/files.
                    importDecodedPrivateKey(key, parameters, privateKeyInput, importPrivateKeyButton, encryptedKey);
                } catch (BIP38PrivateKey.BadPassphraseException error) {
                    runOnUiThread(() -> {
                        importPrivateKeyButton.setEnabled(true);
                        Toast.makeText(this, R.string.bip38_wrong_passphrase, Toast.LENGTH_LONG).show();
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        importPrivateKeyButton.setEnabled(true);
                        Toast.makeText(this, getString(
                                R.string.private_key_import_failed,
                                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                                Toast.LENGTH_LONG).show();
                    });
                }
            }, "wallet-bip38-decrypt").start();
        }));
        dialog.show();
    }

    private void importDecodedPrivateKey(ECKey key,
                                         NetworkParameters parameters,
                                         EditText privateKeyInput,
                                         Button importPrivateKeyButton,
                                         String originalBip38Key) {
        try {
            NetworkParameters addressParameters = parameters;
            if (addressParameters == null) {
                addressParameters = Constants.IS_PRODUCTION
                        ? org.bitcoinj.params.MainNetParams.get()
                        : org.bitcoinj.params.TestNet3Params.get();
            }
            final String addressType = WalletAddressType.normalize(selectedAddressType);
            if (WalletAddressType.requiresCompressedKey(addressType) && !key.isCompressed()) {
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    Toast.makeText(this, R.string.paper_wallet_address_type_compressed_required, Toast.LENGTH_LONG).show();
                });
                return;
            }
            final String importedAddress = WalletAddressType.addressForKey(
                    addressParameters, key, addressType).toString();
            final org.bitcoinj.script.Script importedScript = WalletAddressType.scriptForKey(
                    addressParameters, key, addressType);

            Wallet wallet = getWalletSafely();
            if (wallet == null) {
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_LONG).show();
                });
                return;
            }
            if (WalletSecurity.isEncrypted(wallet) && !WalletSecurity.isSessionValid(wallet)) {
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    Toast.makeText(this, R.string.wallet_locked, Toast.LENGTH_LONG).show();
                });
                return;
            }

            if (!WalletAddressType.isNativelySpendable(addressType)) {
                wallet.addWatchedScripts(Collections.singletonList(importedScript));
            }

            int added = WalletSecurity.isEncrypted(wallet)
                    ? wallet.importKeysAndEncrypt(Collections.singletonList(key), WalletSecurity.getSessionKey())
                    : (wallet.importKey(key) ? 1 : 0);

            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
            if (presenter != null) {
                try {
                    presenter.saveWalletNow();
                } catch (Exception ignored) {
                    // The management entry is already persisted independently.
                }
            }

            // Only register the management entry after the private key is confirmed
            // to be in the active wallet. This prevents a failed/locked import from
            // leaving a stale entry that looks like an imported wallet but has no key.
            if (WalletSelection.findImportedKey(wallet, importedAddress) == null) {
                runOnUiThread(() -> {
                    importPrivateKeyButton.setEnabled(true);
                    Toast.makeText(this, R.string.imported_wallet_not_found, Toast.LENGTH_LONG).show();
                });
                return;
            }

            ImportedWalletStore.register(this, importedAddress);
            ImportedWalletStore.setAddressType(this, importedAddress, addressType);
            int index = ImportedWalletStore.getAddresses(this).indexOf(importedAddress) + 1;
            if (index < 1) index = 1;
            ImportedWalletStore.setName(this, importedAddress,
                    ImportedWalletStore.getName(this, importedAddress, index));
            if (originalBip38Key != null) {
                ImportedWalletStore.setBip38Key(this, importedAddress, originalBip38Key);
            } else {
                ImportedWalletStore.clearBip38Key(this, importedAddress);
            }

            final int result = added;
            runOnUiThread(() -> {
                importPrivateKeyButton.setEnabled(true);
                privateKeyInput.setText("");
                renderImportedWallets();
                refreshImportedWalletSummary();
                new AlertDialog.Builder(this)
                        .setTitle(result == 0
                                ? R.string.private_key_already_present
                                : R.string.private_key_imported)
                        .setMessage(getString(R.string.private_key_import_address, importedAddress))
                        .setPositiveButton(R.string.close, null)
                        .show();
            });
        } catch (Exception error) {
            runOnUiThread(() -> {
                importPrivateKeyButton.setEnabled(true);
                Toast.makeText(this, getString(
                        R.string.private_key_import_failed,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                        Toast.LENGTH_LONG).show();
            });
        }
    }

    /**
     * WalletAppKit can be between stop/start during initial sync or rescan.
     * Never let that transient state crash the Import screen.
     */
    private Wallet getWalletSafely() {
        try {
            WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
            if (kit == null) return null;
            return kit.wallet();
        } catch (IllegalStateException ignored) {
            return null;
        }
    }
    private void attachImportedWalletListener() {
        Wallet wallet = getWalletSafely();
        if (wallet == importedObservedWallet) {
            return;
        }
        detachImportedWalletListener();
        if (wallet == null) {
            return;
        }
        importedObservedWallet = wallet;
        importedWalletChangeListener = changedWallet -> scheduleImportedSummaryRefresh();
        wallet.addChangeEventListener(importedWalletChangeListener);
    }

    private void detachImportedWalletListener() {
        if (importedObservedWallet != null && importedWalletChangeListener != null) {
            importedObservedWallet.removeChangeEventListener(importedWalletChangeListener);
        }
        importedObservedWallet = null;
        importedWalletChangeListener = null;
    }

    private void scheduleImportedSummaryRefresh() {
        if (isFinishing() || isDestroyed() || importedWalletSummary == null || importedRefreshQueued) {
            return;
        }
        importedRefreshQueued = true;
        importedRefreshHandler.postDelayed(() -> {
            importedRefreshQueued = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            attachImportedWalletListener();
            refreshImportedWalletSummary();
        }, IMPORTED_REFRESH_DEBOUNCE_MS);
    }

    private void refreshImportedWalletSummary() {
        if (importedWalletSummary == null) {
            return;
        }

        synchronized (importedRefreshLock) {
            ++importedRefreshGeneration;
            importedRefreshPending = true;
            if (importedRefreshRunning) {
                return;
            }
            importedRefreshRunning = true;
        }

        new Thread(() -> {
            while (true) {
                final long requestedGeneration;
                synchronized (importedRefreshLock) {
                    if (!importedRefreshPending) {
                        importedRefreshRunning = false;
                        return;
                    }
                    importedRefreshPending = false;
                    requestedGeneration = importedRefreshGeneration;
                }

                Wallet wallet = getWalletSafely();
            Coin total = Coin.ZERO;
            if (wallet != null) {
                for (String address : ImportedWalletStore.getAddresses(this)) {
                    org.bitcoinj.script.Script script = WalletSelection.findImportedScriptForAddress(wallet, address);
                    if (script == null) {
                        continue;
                    }
                    Iterable<org.bitcoinj.core.TransactionOutput> outputs = WalletSelection.importedAddressUsesWatchedOutputs(
                            this, address) ? wallet.getWatchedOutputs(false) : wallet.getUnspents();
                    for (org.bitcoinj.core.TransactionOutput output : outputs) {
                        if (output.isAvailableForSpending() && script.equals(output.getScriptPubKey())) {
                            total = total.add(output.getValue());
                        }
                    }
                }
            }
            final String totalText = total.toFriendlyString();
            final java.util.Map<String, ImportedBalance> balances = new java.util.HashMap<>();
            if (wallet != null) {
                try {
                    for (String address : ImportedWalletStore.getAddresses(this)) {
                        org.bitcoinj.script.Script script = WalletSelection.findImportedScriptForAddress(wallet, address);
                        if (script == null) {
                            continue;
                        }
                        ImportedBalance balance = new ImportedBalance();
                        for (org.bitcoinj.core.Transaction transaction : wallet.getTransactions(true)) {
                            for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
                                if (!output.isAvailableForSpending() || !script.equals(output.getScriptPubKey())) {
                                    continue;
                                }
                                long value = output.getValue().value;
                                if (output.getParentTransactionDepthInBlocks() > 0) {
                                    balance.confirmed += value;
                                } else {
                                    balance.pending += value;
                                }
                            }
                        }
                        balances.put(address, balance);
                    }
                } catch (Exception ignored) {
                    // Keep the last rendered card values if the wallet changes while scanning.
                }
            }
            int scannedHeight = 0;
            if (wallet != null) {
                try {
                    scannedHeight = wallet.getLastBlockSeenHeight();
                } catch (Exception ignored) {
                    scannedHeight = 0;
                }
            }
            final int finalScannedHeight = scannedHeight;
            final String finalTotalText = totalText;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || importedWalletSummary == null) {
                    return;
                }
                synchronized (importedRefreshLock) {
                    if (requestedGeneration != importedRefreshGeneration) {
                        return;
                    }
                }
                TextViewUtils.setTextIfChanged(importedWalletSummary, getString(
                        R.string.imported_wallet_total_with_scan, finalTotalText, finalScannedHeight));
                updateImportedWalletBalances(balances);
            });
            synchronized (importedRefreshLock) {
                if (!importedRefreshPending) {
                    importedRefreshRunning = false;
                    return;
                }
            }
            }
        }, "wallet-imported-summary").start();
    }

    private void renderImportedWallets() {
        LinearLayout container = findViewById(R.id.importedWalletsContainer);
        TextView empty = findViewById(R.id.importedWalletsEmpty);
        if (container == null || empty == null) return;

        // IMPORTANT: this method is UI-only. Do not call WalletAppKit.wallet(),
        // getImportedKeys(), getUnspents(), or any bitcoinj balance calculation here.
        // Those operations were causing the Import screen to freeze.
        container.removeAllViews();
        importedWalletRows.clear();
        java.util.List<String> addresses = ImportedWalletStore.getAddresses(this);
        empty.setVisibility(addresses.isEmpty() ? View.VISIBLE : View.GONE);
        for (int i = 0; i < addresses.size(); i++) {
            addImportedWalletRow(container, addresses.get(i), i + 1);
        }
    }

    private void addImportedWalletRow(LinearLayout container, String address, int index) {
        View row = getLayoutInflater().inflate(R.layout.item_imported_wif_wallet, container, false);
        TextView name = row.findViewById(R.id.importedWalletName);
        TextView addressView = row.findViewById(R.id.importedWalletAddress);
        TextView typeView = row.findViewById(R.id.importedWalletType);
        TextViewUtils.configureSelectableMiddleEllipsis(addressView);
        TextView balance = row.findViewById(R.id.importedWalletBalance);
        TextView confirmed = row.findViewById(R.id.importedWalletConfirmed);
        TextView pending = row.findViewById(R.id.importedWalletPending);
        Button select = row.findViewById(R.id.importedWalletSelect);
        Button manage = row.findViewById(R.id.importedWalletManage);

        name.setText(ImportedWalletStore.getName(this, address, index));
        TextViewUtils.setTextIfChanged(typeView, getString(R.string.imported_wallet_address_type_value,
                WalletAddressType.label(this, ImportedWalletStore.getAddressType(this, address))));
        TextViewUtils.setTextIfChanged(addressView, address);
        TextViewUtils.setTextIfChanged(balance, getString(
                R.string.imported_wallet_balance, Coin.ZERO.toFriendlyString()));
        TextViewUtils.setTextIfChanged(confirmed, getString(
                R.string.imported_wallet_confirmed_value, Coin.ZERO.toFriendlyString()));
        TextViewUtils.setTextIfChanged(pending, getString(
                R.string.imported_wallet_pending_value, Coin.ZERO.toFriendlyString()));
        importedWalletRows.put(address, row);

        boolean selected = address.equals(WalletSelection.getSelectedImportedAddress(this));
        select.setText(selected ? R.string.imported_wallet_selected : R.string.imported_wallet_select);
        select.setEnabled(!selected);
        select.setOnClickListener(v -> {
            WalletSelection.selectImportedAddress(this, address);
            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
            if (presenter != null) presenter.refresh();
            renderImportedWallets();
        });
        manage.setOnClickListener(v -> showImportedWalletManager(address));

        // The row is inflated with the container as parent but must still be
        // explicitly attached. Without this call the empty-state text hides
        // after import, while the wallet row remains invisible.
        container.addView(row);
    }

    private void updateImportedWalletBalances(java.util.Map<String, ImportedBalance> balances) {
        for (java.util.Map.Entry<String, View> entry : importedWalletRows.entrySet()) {
            ImportedBalance value = balances.get(entry.getKey());
            if (value == null) {
                continue;
            }
            TextView balance = entry.getValue().findViewById(R.id.importedWalletBalance);
            TextView confirmed = entry.getValue().findViewById(R.id.importedWalletConfirmed);
            TextView pending = entry.getValue().findViewById(R.id.importedWalletPending);
            TextViewUtils.setTextIfChanged(balance, getString(
                    R.string.imported_wallet_balance,
                    Coin.valueOf(value.total()).toFriendlyString()));
            TextViewUtils.setTextIfChanged(confirmed, getString(
                    R.string.imported_wallet_confirmed_value,
                    Coin.valueOf(value.confirmed).toFriendlyString()));
            TextViewUtils.setTextIfChanged(pending, getString(
                    R.string.imported_wallet_pending_value,
                    Coin.valueOf(value.pending).toFriendlyString()));
        }
    }

    private void showImportedWalletManager(String address) {
        int index = ImportedWalletStore.getAddresses(this).indexOf(address) + 1;
        String currentName = ImportedWalletStore.getName(this, address, Math.max(1, index));

        String[] actions = new String[] {
                getString(R.string.imported_wallet_show_address_qr),
                getString(R.string.imported_wallet_show_wif_qr),
                getString(R.string.imported_wallet_rename),
                getString(R.string.imported_wallet_remove)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.imported_wallet_manage_title) + "\n" + currentName)
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) showImportedAddress(address);
                    else if (which == 1) showImportedWif(address);
                    else if (which == 2) renameImportedWallet(address, currentName);
                    else if (which == 3) confirmRemoveImportedWallet(address, currentName);
                })
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showImportedAddress(String address) {
        showValueWithQr(
                R.string.imported_wallet_address_title,
                address,
                R.string.imported_wallet_copy_address,
                R.string.imported_wallet_address_copied);
    }

    private void showImportedWif(String address) {
        String bip38Key = ImportedWalletStore.getBip38Key(this, address);
        if (bip38Key != null) {
            promptForBip38Display(address, bip38Key);
            return;
        }

        new Thread(() -> {
            try {
                Wallet wallet = getWalletSafely();
                ECKey key = WalletSelection.findImportedKey(wallet, address);
                if (key == null) {
                    runOnUiThread(() -> show(R.string.imported_wallet_not_found));
                    return;
                }
                if (key.isEncrypted()) {
                    if (!WalletSecurity.isSessionValid(wallet)) {
                        runOnUiThread(() -> Toast.makeText(this, R.string.wallet_locked, Toast.LENGTH_LONG).show());
                        return;
                    }
                    key = key.decrypt(key.getKeyCrypter(), WalletSecurity.getSessionKey());
                }
                final String wif = key.getPrivateKeyEncoded(getParameters()).toString();
                runOnUiThread(() -> showValueWithQr(
                        R.string.imported_wallet_wif_title,
                        wif,
                        R.string.imported_wallet_copy_wif,
                        R.string.imported_wallet_wif_copied));
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, getString(
                        R.string.imported_wallet_wif_show_failed,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        }, "wallet-show-wif").start();
    }

    private void promptForBip38Display(String address, String encryptedWif) {
        EditText passphraseInput = new EditText(this);
        passphraseInput.setSingleLine(true);
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        passphraseInput.setHint(R.string.bip38_passphrase_hint);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.bip38_show_wif_title)
                .setMessage(R.string.bip38_show_wif_message)
                .setView(passphraseInput)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.bip38_unlock, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String passphrase = passphraseInput.getText().toString();
            if (passphrase.isEmpty()) {
                Toast.makeText(this, R.string.bip38_passphrase_required, Toast.LENGTH_LONG).show();
                return;
            }
            dialog.dismiss();
            new Thread(() -> {
                try {
                    BIP38PrivateKey bip38 = BIP38PrivateKey.fromBase58(
                            Constants.IS_PRODUCTION ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET,
                            encryptedWif);
                    ECKey key = bip38.decrypt(passphrase);
                    String addressType = ImportedWalletStore.getAddressType(this, address);
                    String derivedAddress = WalletAddressType.addressForKey(
                            getParameters(), key, addressType).toString();
                    if (!address.equals(derivedAddress)) {
                        runOnUiThread(() -> Toast.makeText(this, R.string.bip38_wrong_passphrase, Toast.LENGTH_LONG).show());
                        return;
                    }
                    runOnUiThread(() -> showValueWithQr(
                            R.string.imported_wallet_wif_title,
                            encryptedWif,
                            R.string.imported_wallet_copy_wif,
                            R.string.imported_wallet_wif_copied));
                } catch (BIP38PrivateKey.BadPassphraseException error) {
                    runOnUiThread(() -> Toast.makeText(this, R.string.bip38_wrong_passphrase, Toast.LENGTH_LONG).show());
                } catch (Exception error) {
                    runOnUiThread(() -> Toast.makeText(this, getString(
                            R.string.imported_wallet_wif_show_failed,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                            Toast.LENGTH_LONG).show());
                }
            }, "wallet-show-bip38-wif").start();
        }));
        dialog.show();
    }

    private void showValueWithQr(int titleRes, String value, int copyLabelRes, int copiedMessageRes) {
        if (TextUtils.isEmpty(value)) return;
        View content = getLayoutInflater().inflate(R.layout.dialog_receive_qr, null);
        android.widget.ImageView image = content.findViewById(R.id.receiveQrImage);
        TextView valueText = content.findViewById(R.id.receiveQrAddress);
        TextViewUtils.configureSelectableMiddleEllipsis(valueText);
        TextViewUtils.setTextIfChanged(valueText, value);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setView(content)
                .setPositiveButton(copyLabelRes, (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                getString(copyLabelRes), value));
                        show(copiedMessageRes);
                    }
                })
                .setNegativeButton(R.string.close, null)
                .create();
        dialog.show();

        new Thread(() -> {
            try {
                final android.graphics.Bitmap qr = wallet.qr.QrCodeGenerator.generate(value, 800);
                runOnUiThread(() -> {
                    if (dialog.isShowing()) image.setImageBitmap(qr);
                });
            } catch (Exception ignored) {
            }
        }, "wallet-value-qr").start();
    }

    private void renameImportedWallet(String address, String currentName) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentName);
        input.setSelection(input.length());
        input.setHint(R.string.imported_wallet_name_hint);
        new AlertDialog.Builder(this)
                .setTitle(R.string.imported_wallet_rename_title)
                .setView(input)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.imported_wallet_rename, (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        ImportedWalletStore.setName(this, address, value);
                        renderImportedWallets();
                        show(R.string.imported_wallet_renamed);
                    }
                })
                .show();
    }

    private void confirmRemoveImportedWallet(String address, String name) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.imported_wallet_remove_title)
                .setMessage(R.string.imported_wallet_remove_message)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.imported_wallet_remove, (d, w) -> removeImportedWallet(address))
                .show();
    }

    private void removeImportedWallet(String address) {
        Wallet wallet = getWallet();
        if (wallet == null) return;
        try {
            org.bitcoinj.crypto.ECKey key = WalletSelection.findImportedKey(wallet, address);
            if (key == null) throw new IllegalStateException(getString(R.string.imported_wallet_not_found));
            String addressType = ImportedWalletStore.getAddressType(this, address);
            org.bitcoinj.script.Script importedScript = WalletAddressType.scriptForKey(
                    wallet.getParams(), key, addressType);
            if (!WalletAddressType.isNativelySpendable(addressType)) {
                wallet.removeWatchedScripts(Collections.singletonList(importedScript));
            }
            ImportedWalletStore.remove(this, address);
            if (!WalletSelection.isKeyUsedByOtherImportedAddresses(this, wallet, address, key)) {
                wallet.removeKey(key);
            }
            if (address.equals(WalletSelection.getSelectedImportedAddress(this))) {
                WalletSelection.selectMain(this);
            }
            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
            if (presenter != null) {
                presenter.saveWalletNow();
                presenter.refresh();
            }
            renderImportedWallets();
            show(R.string.imported_wallet_removed);
        } catch (Exception error) {
            Toast.makeText(this, getString(
                    R.string.imported_wallet_remove_failed,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private String addressTypeFromCheckedId(int checkedId) {
        if (checkedId == R.id.walletAddressTypeP2wpkh) return WalletAddressType.P2WPKH;
        if (checkedId == R.id.walletAddressTypeP2shP2wpkh) return WalletAddressType.P2SH_P2WPKH;
        if (checkedId == R.id.walletAddressTypeP2tr) return WalletAddressType.P2TR;
        return WalletAddressType.P2PKH;
    }

    private void showWifInfo() {
        TextView content = (TextView) getLayoutInflater().inflate(R.layout.dialog_text, null);
        content.setText(R.string.wif_info_message);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.what_is_wif)
                .setView(content)
                .setPositiveButton(R.string.close, null)
                .show();
    }
}
