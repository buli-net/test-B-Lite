package wallet.tools;

import wallet.main.BaseActivity;

import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.widget.TextView;
import android.widget.Toast;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.wallet.Wallet;
import wallet.main.MainActivityPresenter;
import wallet.main.R;
import androidx.appcompat.app.AlertDialog;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import androidx.activity.result.ActivityResultLauncher;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import org.bitcoinj.base.Address;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.listeners.WalletChangeEventListener;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import wallet.ui.TextViewUtils;

public final class WatchOnlyWalletActivity extends BaseActivity {
    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (!TextUtils.isEmpty(result.getContents())) {
                    EditText input = findViewById(R.id.watchAddressInput);
                    input.setText(result.getContents().trim());
                    input.setSelection(input.length());
                }
            });
    private EditText watchAddressInput;
    private EditText watchDateInput;
    private Button addWatchAddressButton;
    private TextView watchedAddressEmpty;
    private Button deleteWatchedButton;
    private LinearLayout watchedAddressList;
    private TextView watchedBalanceSummary;
    private Wallet watchedWallet;
    private WalletChangeEventListener walletChangeListener;
    private final Set<Script> selectedWatchedScripts = new HashSet<>();
    private final Map<String, View> watchedRows = new HashMap<>();
    private MainActivityPresenter syncPresenter;
    private final android.os.Handler watchRefreshHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean watchRefreshQueued;
    private static final long WATCH_REFRESH_DEBOUNCE_MS = 500L;
    private final Runnable syncStateListener = () -> runOnUiThread(() -> {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        attachWalletListener();
        scheduleWatchedRefresh();
    });

    private Wallet getWallet(){
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null || !presenter.isWalletReady()) {
            return null;
        }
        // wallet() is acquired atomically with the WalletAppKit lifecycle check.
        // During a rescan/restart this simply returns null until the replacement kit is ready.
        return MainActivityPresenter.getActiveWallet();
    }
    private NetworkParameters getParameters() {
        return MainActivityPresenter.getActiveParameters();
    }

    private void show(int messageId) {
        Toast.makeText(this, messageId, Toast.LENGTH_LONG).show();
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); setContentView(R.layout.activity_watch_only_wallet); Toolbar toolbar=findViewById(R.id.toolbar_watch_only); setSupportActionBar(toolbar);
        if(getSupportActionBar()!=null){getSupportActionBar().setTitle(R.string.watch_only_title);getSupportActionBar().setDisplayHomeAsUpEnabled(true);}
        toolbar.setNavigationOnClickListener(v->finish());
        syncPresenter = MainActivityPresenter.getActivePresenter();
        if (syncPresenter != null) {
            syncPresenter.addSyncStateListener(syncStateListener);
        }
        watchAddressInput=findViewById(R.id.watchAddressInput); watchDateInput=findViewById(R.id.watchDateInput); addWatchAddressButton=findViewById(R.id.addWatchAddressButton); deleteWatchedButton=findViewById(R.id.deleteWatchedButton); watchedBalanceSummary=findViewById(R.id.watchedBalanceSummary); watchedAddressList=findViewById(R.id.watchedAddressList); watchedAddressEmpty=findViewById(R.id.watchedAddressEmpty);
        Button scanWatchAddressQrButton = findViewById(R.id.scanWatchAddressQrButton);
        addWatchAddressButton.setOnClickListener(v->addWatchAddress());
        scanWatchAddressQrButton.setOnClickListener(v -> {
            ScanOptions options = new ScanOptions()
                    .setPrompt(getString(R.string.scan_watch_address_qr));
            barcodeLauncher.launch(options);
        });
        deleteWatchedButton.setOnClickListener(v->deleteSelectedWatchedAddresses()); attachWalletListener(); refreshWatchedAddresses();
    }

    @Override protected void onResume(){
        super.onResume();
        if (syncPresenter == null) {
            syncPresenter = MainActivityPresenter.getActivePresenter();
        }
        if (syncPresenter != null) {
            syncPresenter.addSyncStateListener(syncStateListener);
        }
        attachWalletListener();
        refreshWatchedAddresses();
    }
    @Override protected void onPause(){
        if (syncPresenter != null) {
            syncPresenter.removeSyncStateListener(syncStateListener);
        }
        detachWalletListener();
        watchRefreshHandler.removeCallbacksAndMessages(null);
        watchRefreshQueued = false;
        super.onPause();
    }
    @Override protected void onDestroy(){
        if (syncPresenter != null) {
            syncPresenter.removeSyncStateListener(syncStateListener);
        }
        detachWalletListener();
        watchRefreshHandler.removeCallbacksAndMessages(null);
        watchRefreshQueued = false;
        super.onDestroy();
    }

    private void addWatchAddress() {
        String encoded = watchAddressInput.getText().toString().trim();
        if (TextUtils.isEmpty(encoded)) {
            show(R.string.watch_address_required);
            return;
        }

        Wallet wallet = getWallet();
        NetworkParameters parameters = getParameters();
        if (wallet == null || parameters == null) {
            show(R.string.wallet_not_ready);
            return;
        }

        final Address address;
        try {
            address = Address.fromString(parameters, encoded);
        } catch (Exception error) {
            show(R.string.watch_address_invalid);
            return;
        }

        Instant creationTime;
        try {
            creationTime = parseDate(watchDateInput.getText().toString().trim());
        } catch (Exception error) {
            show(R.string.invalid_wallet_birthday);
            return;
        }

        addWatchAddressButton.setEnabled(false);
        new Thread(() -> {
            try {
                boolean added = creationTime == null
                        ? wallet.addWatchedAddress(address)
                        : wallet.addWatchedAddress(address, creationTime);
                MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                if (presenter != null) {
                    presenter.saveWalletNow();
                }
                runOnUiThread(() -> {
                    addWatchAddressButton.setEnabled(true);
                    if (added) {
                        watchAddressInput.setText("");
                        String scanDate = watchDateInput.getText().toString().trim();
                        watchDateInput.setText("");
                        refreshWatchedAddresses();
                        Toast.makeText(this, R.string.watch_address_added, Toast.LENGTH_LONG).show();
                        // A rescan replaces WalletAppKit and the SPV chain. Do not start that
                        // replacement in the same UI turn as the wallet mutation: on a fresh
                        // install the wallet, presenter, and watchdog can still be settling.
                        // If normal sync is active, leave the explicit Rescan action to the user.
                        MainActivityPresenter activePresenter = MainActivityPresenter.getActivePresenter();
                        if (activePresenter != null && !activePresenter.isSyncing()) {
                            Instant scanFrom = null;
                            try {
                                scanFrom = TextUtils.isEmpty(scanDate)
                                        ? Instant.ofEpochSecond(1231006505L)
                                        : parseDate(scanDate);
                            } catch (Exception ignored) {
                                // The date was already validated above.
                            }
                            if (scanFrom != null) {
                                final Instant finalScanFrom = scanFrom;
                                new android.os.Handler(getMainLooper()).postDelayed(
                                        () -> rescanWatchedAddresses(finalScanFrom),
                                        1000L);
                            }
                        }
                    } else {
                        refreshWatchedAddresses();
                        Toast.makeText(this, R.string.watch_address_already_present, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    addWatchAddressButton.setEnabled(true);
                    Toast.makeText(this, getString(
                            R.string.watch_address_failed,
                            error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "wallet-watch-address").start();
    }
    private void rescanWatchedAddresses(final Instant scanFrom) {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        if (presenter == null) {
            show(R.string.wallet_not_ready);
            return;
        }
        presenter.rescanWatchedAddresses(scanFrom, error -> runOnUiThread(() -> {
            refreshWatchedAddresses();
            if (error != null) {
                Toast.makeText(this, getString(
                        R.string.watch_rescan_failed,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.watch_rescan_started, Toast.LENGTH_LONG).show();
            }
        }));
    }
    private void deleteSelectedWatchedAddresses() {
        if (selectedWatchedScripts.isEmpty()) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.watch_delete_title)
                .setMessage(R.string.watch_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.watch_delete_button, (dialog, which) -> {
                    Wallet wallet = getWallet();
                    if (wallet == null) {
                        show(R.string.wallet_not_ready);
                        return;
                    }
                    Set<Script> toDelete = new HashSet<>(selectedWatchedScripts);
                    new Thread(() -> {
                        try {
                            wallet.removeWatchedScripts(new ArrayList<>(toDelete));
                            MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
                            if (presenter != null) {
                                presenter.saveWalletNow();
                            }
                            runOnUiThread(() -> {
                                selectedWatchedScripts.clear();
                                refreshWatchedAddresses();
                                Toast.makeText(this, R.string.watch_deleted, Toast.LENGTH_LONG).show();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> Toast.makeText(this, getString(
                                    R.string.watch_delete_failed,
                                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                                    Toast.LENGTH_LONG).show());
                        }
                    }, "wallet-delete-watched").start();
                })
                .show();
    }
    private void attachWalletListener() {
        Wallet wallet = getWallet();
        if (wallet == watchedWallet) {
            return;
        }

        detachWalletListener();
        if (wallet == null) {
            return;
        }

        watchedWallet = wallet;
        walletChangeListener = changedWallet -> scheduleWatchedRefresh();
        wallet.addChangeEventListener(walletChangeListener);
    }
    private void detachWalletListener() {
        if (watchedWallet != null && walletChangeListener != null) {
            watchedWallet.removeChangeEventListener(walletChangeListener);
        }
        watchedWallet = null;
        walletChangeListener = null;
    }
    private void scheduleWatchedRefresh() {
        if (isFinishing() || isDestroyed() || watchedAddressList == null) {
            return;
        }
        if (watchRefreshQueued) {
            return;
        }
        watchRefreshQueued = true;
        watchRefreshHandler.postDelayed(() -> {
            watchRefreshQueued = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            refreshWatchedAddresses();
        }, WATCH_REFRESH_DEBOUNCE_MS);
    }

    private void refreshWatchedAddresses() {
        Wallet wallet = getWallet();
        if (wallet == null || watchedAddressList == null) {
            return;
        }

        List<Script> scripts = wallet.getWatchedScripts();
        selectedWatchedScripts.retainAll(scripts);

        if (scripts == null || scripts.isEmpty()) {
            watchedRows.clear();
            if (watchedAddressList.getChildCount() != 0) {
                watchedAddressList.removeAllViews();
            }
            if (watchedAddressEmpty != null) {
                watchedAddressEmpty.setVisibility(View.VISIBLE);
            }
            TextViewUtils.setTextIfChanged(watchedBalanceSummary, getWatchSummary(
                    org.bitcoinj.base.Coin.ZERO.toFriendlyString()));
            TextView empty = (TextView) getLayoutInflater().inflate(
                    R.layout.item_empty_message, watchedAddressList, false);
            empty.setText(R.string.watch_address_empty);
            watchedAddressList.addView(empty);
            updateDeleteButton();
            return;
        }

        if (watchedAddressEmpty != null) {
            watchedAddressEmpty.setVisibility(View.GONE);
        }

        for (int i = watchedAddressList.getChildCount() - 1; i >= 0; i--) {
            View child = watchedAddressList.getChildAt(i);
            if (!(child.getTag() instanceof String)) {
                watchedAddressList.removeViewAt(i);
            }
        }

        NetworkParameters parameters = getParameters();
        // Use bitcoinj's dedicated watched-output API. It returns the currently unspent
        // outputs whose script exactly matches an address/script registered as watched.
        // This keeps normal wallet UTXOs out of the watch-only totals.
        List<TransactionOutput> outputs = wallet.getWatchedOutputs(false);
        Map<Script, WatchBalance> balances = new HashMap<>();
        for (Script script : scripts) {
            balances.put(script, new WatchBalance());
        }

        for (TransactionOutput output : outputs) {
            if (!output.isAvailableForSpending()) {
                continue;
            }
            Script outputScript = output.getScriptPubKey();
            WatchBalance balance = balances.get(outputScript);
            if (balance == null) {
                continue;
            }
            long value = output.getValue().value;
            if (output.getParentTransactionDepthInBlocks() > 0) {
                balance.confirmed += value;
            } else {
                balance.pending += value;
            }
        }

        long watchedTotal = 0L;
        Set<String> currentKeys = new HashSet<>();
        for (int index = 0; index < scripts.size(); index++) {
            Script script = scripts.get(index);
            WatchBalance balance = balances.get(script);
            watchedTotal += balance.total();

            String key = script.toString();
            currentKeys.add(key);
            View row = watchedRows.get(key);
            if (row == null) {
                row = LayoutInflater.from(this)
                        .inflate(R.layout.item_watch_address, watchedAddressList, false);
                bindWatchedRow(row, script);
                watchedRows.put(key, row);
            }

            updateWatchedRow(row, script, parameters, balance);

            // Existing rows stay in place while sync advances. Only add/reorder when
            // the actual watched-script set/order changed, so a changing scan height
            // does not recreate every address card and its ellipsis/selection state.
            if (index >= watchedAddressList.getChildCount()
                    || watchedAddressList.getChildAt(index) != row) {
                int currentIndex = watchedAddressList.indexOfChild(row);
                if (currentIndex >= 0) {
                    watchedAddressList.removeViewAt(currentIndex);
                }
                watchedAddressList.addView(row, Math.min(index, watchedAddressList.getChildCount()));
            }
        }

        for (int i = watchedAddressList.getChildCount() - 1; i >= 0; i--) {
            View child = watchedAddressList.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof String && !currentKeys.contains((String) tag)) {
                watchedAddressList.removeViewAt(i);
            }
        }
        watchedRows.keySet().retainAll(currentKeys);

        TextViewUtils.setTextIfChanged(watchedBalanceSummary, getWatchSummary(
                org.bitcoinj.base.Coin.valueOf(watchedTotal).toFriendlyString()));
        updateDeleteButton();
    }

    private void bindWatchedRow(View row, Script script) {
        row.setTag(script.toString());
        CheckBox checkBox = row.findViewById(R.id.watchAddressCheckBox);
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                selectedWatchedScripts.add(script);
            } else {
                selectedWatchedScripts.remove(script);
            }
            updateDeleteButton();
        });

        TextView addressView = row.findViewById(R.id.watchAddressValue);
        TextViewUtils.configureSelectableMiddleEllipsis(addressView);
    }

    private void updateWatchedRow(View row, Script script, NetworkParameters parameters,
                                  WatchBalance balance) {
        CheckBox checkBox = row.findViewById(R.id.watchAddressCheckBox);
        TextView typeView = row.findViewById(R.id.watchAddressType);
        TextView addressView = row.findViewById(R.id.watchAddressValue);
        TextView confirmedView = row.findViewById(R.id.watchAddressConfirmed);
        TextView pendingView = row.findViewById(R.id.watchAddressPending);
        TextView totalView = row.findViewById(R.id.watchAddressTotal);

        String type = script.getScriptType() == null
                ? getString(R.string.watch_script_type_unknown)
                : script.getScriptType().name();
        String address;
        try {
            address = script.getToAddress(parameters).toString();
        } catch (Exception error) {
            address = script.toString();
        }

        TextViewUtils.setTextIfChanged(typeView, type);
        TextViewUtils.setTextIfChanged(addressView, address);
        TextViewUtils.setTextIfChanged(confirmedView, getString(
                R.string.watch_confirmed_value,
                org.bitcoinj.base.Coin.valueOf(balance.confirmed).toFriendlyString()));
        TextViewUtils.setTextIfChanged(pendingView, getString(
                R.string.watch_pending_value,
                org.bitcoinj.base.Coin.valueOf(balance.pending).toFriendlyString()));
        TextViewUtils.setTextIfChanged(totalView,
                org.bitcoinj.base.Coin.valueOf(balance.total()).toFriendlyString());

        boolean selected = selectedWatchedScripts.contains(script);
        if (checkBox.isChecked() != selected) {
            checkBox.setOnCheckedChangeListener(null);
            checkBox.setChecked(selected);
            checkBox.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selectedWatchedScripts.add(script);
                } else {
                    selectedWatchedScripts.remove(script);
                }
                updateDeleteButton();
            });
        }
    }
    private String getWatchSummary(String total) {
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        int lastScanned = presenter == null ? 0 : presenter.getWalletLastSeenHeight();
        return getString(R.string.watch_balance_total_with_scan, total, lastScanned);
    }
    private void updateDeleteButton() {
        if (deleteWatchedButton != null) {
            deleteWatchedButton.setEnabled(!selectedWatchedScripts.isEmpty());
        }
    }
    private Instant parseDate(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return LocalDate.parse(value)
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }
    private static final class WatchBalance {
        private long confirmed;
        private long pending;

        private long total() {
            return confirmed + pending;
        }
    }
}
