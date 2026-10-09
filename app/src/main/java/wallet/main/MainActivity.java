package wallet.main;
import wallet.widget.BalanceWidgetProvider;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import android.os.Bundle;
import android.os.Build;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.appcompat.widget.Toolbar;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import java.util.ArrayList;
import java.util.List;

import wallet.Constants;
import wallet.model.TransactionItem;
import wallet.qr.QrCodeGenerator;
import wallet.qr.ReceiveQrDialog;
import wallet.request.RequestCoinsActivity;
import wallet.transaction.TransactionAdapter;
import wallet.storage.WalletFileMigration;
import wallet.ui.TextViewUtils;

/** Main wallet screen. UI structure lives in XML; this class handles wallet events. */
public class MainActivity extends BaseActivity
        implements MainActivityContract.MainActivityView {

    public static final String ACTION_SHOW_WIDGET_QR =
            "wallet.widget.action.SHOW_QR";
    public static final String EXTRA_WIDGET_QR_ADDRESS =
            "wallet.widget.extra.QR_ADDRESS";
    private static final String ACTION_SHORTCUT_SCAN =
            "wallet.main.action.SHORTCUT_SCAN";
    private static final String ACTION_SHORTCUT_REQUEST =
            "wallet.main.action.SHORTCUT_REQUEST";

    private final ActivityResultLauncher<ScanOptions> barcodeLauncher =
            registerForActivityResult(new ScanContract(), result -> {
                if (!TextUtils.isEmpty(result.getContents())) {
                    handleScannedAddress(result.getContents());
                }
            });

    private final ActivityResultLauncher<Intent> addressBookLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String selectedAddress = result.getData().getStringExtra(
                            wallet.contacts.AddressBookActivity.EXTRA_SELECTED_ADDRESS);
                    if (!TextUtils.isEmpty(selectedAddress)) {
                        Intent send = new Intent(this, wallet.send.SendActivity.class);
                        send.putExtra(wallet.send.SendActivity.EXTRA_RECIPIENT, selectedAddress);
                        startActivity(send);
                    }
                }
            });

    private final ActivityResultLauncher<String> transactionExportLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), uri -> {
                if (uri != null) {
                    exportTransactionsTo(uri);
                }
            });

    private static final String STATE_TRANSACTION_FILTER = "transaction_filter";
    private static final String STATE_TRANSACTION_LIMIT = "transaction_limit";
    private static final int FILTER_ALL = 0;
    private static final int FILTER_SENT = 1;
    private static final int FILTER_RECEIVED = 2;

    private MainActivityContract.MainActivityPresenter presenter;

    private FrameLayout startupSplash;
    private Toolbar toolbar;
    private SwipeRefreshLayout swipeRefresh;
    private TextView balanceText;
    private TextView availableBalanceText;
    private TextView pendingBalanceText;
    private TextView networkBadge;
    private TextView addressText;
    private ImageView copyAddress;
    private ImageView receiveQr;
    private String lastReceiveQrAddress;
    private int receiveQrGeneration;
    private ImageButton walletSelectorButton;
    private TextView walletTypeText;

    private final List<TransactionItem> allTransactions = new ArrayList<>();
    private final List<TransactionItem> filteredTransactions = new ArrayList<>();

    private TransactionAdapter transactionAdapter;
    private TextView transactionsEmpty;
    private TextView transactionsCount;
    private ImageButton transactionsExpand;
    private android.widget.Button transactionsExport;
    private RadioGroup transactionFilters;
    private int transactionFilter = FILTER_ALL;
    /** -1 means all matching transactions. */
    private int transactionLimit = -1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        requestNotificationPermissionIfNeeded();

        if (state != null) {
            transactionFilter = state.getInt(STATE_TRANSACTION_FILTER, FILTER_ALL);
            transactionLimit = state.getInt(STATE_TRANSACTION_LIMIT, -1);
        }

        bindViews();
        setupToolbar();
        updateNetworkBadge();
        setupTransactionList();
        setupTransactionFilters();
        setupActions();
        initPresenter();
        showStartupSplash();
        handleWidgetQrIntent(getIntent());
        handleShortcutIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleWidgetQrIntent(intent);
        handleShortcutIntent(intent);
    }

    private void handleShortcutIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        intent.setAction(null);
        if (ACTION_SHORTCUT_SCAN.equals(action)) {
            getWindow().getDecorView().postDelayed(this::openScanner, 950L);
        } else if (ACTION_SHORTCUT_REQUEST.equals(action)) {
            getWindow().getDecorView().postDelayed(
                    () -> startActivity(new Intent(this, RequestCoinsActivity.class)), 950L);
        }
    }

    private void handleWidgetQrIntent(Intent intent) {
        if (intent == null || !ACTION_SHOW_WIDGET_QR.equals(intent.getAction())) {
            return;
        }
        final String address = intent.getStringExtra(EXTRA_WIDGET_QR_ADDRESS);
        intent.setAction(null);
        if (TextUtils.isEmpty(address)) {
            return;
        }
        getWindow().getDecorView().postDelayed(
                () -> ReceiveQrDialog.show(this, address),
                950L);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    4101);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // WalletSelection is shared with SendActivity. Refresh the Main screen
        // when returning from Send so a selection made there is reflected here.
        updateNetworkBadge();
        if (presenter != null) {
            presenter.refresh();
        }
    }

    private void bindViews() {
        startupSplash = findViewById(R.id.startupSplash);
        toolbar = findViewById(R.id.toolbar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        balanceText = findViewById(R.id.balanceText);
        availableBalanceText = findViewById(R.id.availableBalanceText);
        pendingBalanceText = findViewById(R.id.pendingBalanceText);
        networkBadge = findViewById(R.id.networkBadge);
        addressText = findViewById(R.id.addressText);
        TextViewUtils.configureSelectableMiddleEllipsis(addressText);
        copyAddress = findViewById(R.id.copyAddress);
        receiveQr = findViewById(R.id.receiveQr);
        walletSelectorButton = findViewById(R.id.walletSelectorButton);
        walletTypeText = findViewById(R.id.walletTypeText);
    }

    private void updateNetworkBadge() {
        if (networkBadge == null) {
            return;
        }
        org.bitcoinj.base.BitcoinNetwork network = NetworkConfig.get(this);
        networkBadge.setText(network == org.bitcoinj.base.BitcoinNetwork.SIGNET
                ? R.string.network_badge_signet
                : R.string.network_badge_mainnet);
    }

    private void setupToolbar() {
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.wallet_title);
        }
    }

    private void setupTransactionList() {
        RecyclerView list = findViewById(R.id.transactionsList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setNestedScrollingEnabled(false);
        transactionAdapter = new TransactionAdapter(filteredTransactions);
        list.setAdapter(transactionAdapter);
        transactionsEmpty = findViewById(R.id.transactionsEmpty);
        transactionsCount = findViewById(R.id.transactionsCount);
        transactionsExpand = findViewById(R.id.transactionsExpand);
        transactionsExport = findViewById(R.id.transactionsExport);
    }

    private void setupTransactionFilters() {
        transactionFilters = findViewById(R.id.transactionFilters);
        transactionFilters.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.filterSent) {
                selectTransactionFilter(FILTER_SENT);
            } else if (checkedId == R.id.filterReceived) {
                selectTransactionFilter(FILTER_RECEIVED);
            } else {
                selectTransactionFilter(FILTER_ALL);
            }
        });
        updateTransactionFilter();
        updateTransactionLimitLabel();
        transactionsExpand.setOnClickListener(v -> showTransactionLimitDialog());
        transactionsExport.setOnClickListener(v -> exportTransactions());
    }

    private void selectTransactionFilter(int selectedFilter) {
        transactionFilter = selectedFilter;
        applyTransactionFilter();
    }

    private void updateTransactionFilter() {
        int checkedId;
        if (transactionFilter == FILTER_SENT) {
            checkedId = R.id.filterSent;
        } else if (transactionFilter == FILTER_RECEIVED) {
            checkedId = R.id.filterReceived;
        } else {
            checkedId = R.id.filterAll;
        }
        transactionFilters.check(checkedId);
    }

    private void applyTransactionFilter() {
        filteredTransactions.clear();
        String sent = getString(R.string.transaction_sent);
        String received = getString(R.string.transaction_received);

        int sentCount = 0;
        int receivedCount = 0;
        int otherCount = 0;
        for (TransactionItem item : allTransactions) {
            boolean include = transactionFilter == FILTER_ALL
                    || (transactionFilter == FILTER_SENT && sent.equals(item.type))
                    || (transactionFilter == FILTER_RECEIVED && received.equals(item.type));
            if (!include) {
                continue;
            }

            if (transactionLimit >= 0) {
                if (sent.equals(item.type)) {
                    if (sentCount >= transactionLimit) continue;
                    sentCount++;
                } else if (received.equals(item.type)) {
                    if (receivedCount >= transactionLimit) continue;
                    receivedCount++;
                } else {
                    if (transactionFilter == FILTER_ALL && otherCount >= transactionLimit) continue;
                    otherCount++;
                }
            }
            filteredTransactions.add(item);
        }

        transactionAdapter.notifyDataSetChanged();
        transactionsEmpty.setVisibility(
                filteredTransactions.isEmpty() ? View.VISIBLE : View.GONE);
        updateTransactionLimitLabel();
    }

    private void updateTransactionLimitLabel() {
        if (transactionsCount == null) {
            return;
        }
        String filterLabel;
        if (transactionFilter == FILTER_SENT) {
            filterLabel = getString(R.string.filter_sent);
        } else if (transactionFilter == FILTER_RECEIVED) {
            filterLabel = getString(R.string.filter_received);
        } else {
            filterLabel = getString(R.string.filter_all);
        }
        String countLabel = transactionLimit < 0
                ? getString(R.string.transaction_limit_all)
                : getString(R.string.transaction_limit_per_type, transactionLimit);
        transactionsCount.setText(getString(
                R.string.transactions_summary,
                filterLabel,
                countLabel,
                filteredTransactions.size(),
                allTransactions.size()));
    }

    private void showTransactionLimitDialog() {
        final String[] labels = {
                getString(R.string.transaction_limit_all),
                getString(R.string.transaction_limit_10),
                getString(R.string.transaction_limit_20),
                getString(R.string.transaction_limit_30),
                getString(R.string.transaction_limit_50),
                getString(R.string.transaction_limit_100)
        };
        final int[] values = {-1, 10, 20, 30, 50, 100};
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == transactionLimit) {
                checked = i;
                break;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.transaction_limit_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    transactionLimit = values[which];
                    applyTransactionFilter();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void setupActions() {

        swipeRefresh.setOnRefreshListener(() -> {
            if (presenter != null) {
                presenter.refresh();
            }
        });

        copyAddress.setOnClickListener(v -> copyAddress());
        addressText.setOnClickListener(v -> showAddressTools());
        receiveQr.setOnClickListener(v -> showReceiveQr());
        walletSelectorButton.setOnClickListener(v -> showWalletSelector());
    }

    private void initPresenter() {
        if (NetworkConfig.get(this) == org.bitcoinj.base.BitcoinNetwork.MAINNET) {
            WalletFileMigration.migrate(this, Constants.WALLET_NAME);
        }

        MainActivityPresenter active = MainActivityPresenter.getActivePresenter();
        if (active != null) {
            presenter = active;
            presenter.attachView(this);
        } else {
            presenter = new MainActivityPresenter(this);
        }

        BitcoinSyncService.start(this);
        presenter.subscribe();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        if (menu instanceof MenuBuilder) {
            ((MenuBuilder) menu).setOptionalIconsVisible(true);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menuScan) {
            openScanner();
            return true;
        } else if (itemId == R.id.menuSend) {
            startActivity(new Intent(this, wallet.send.SendActivity.class));
            return true;
        } else if (itemId == R.id.menuRequestLegacy) {
            startActivity(new Intent(this, RequestCoinsActivity.class));
            return true;
        } else if (itemId == R.id.menuSecurity) {
            startActivity(new Intent(this, wallet.security.SecurityActivity.class));
            return true;
        } else if (itemId == R.id.menuAddressBook) {
            addressBookLauncher.launch(
                    new Intent(this, wallet.contacts.AddressBookActivity.class));
            return true;
        } else if (itemId == R.id.menuImportWif) {
            startActivity(new Intent(this, wallet.tools.WalletImportWifActivity.class));
            return true;
        } else if (itemId == R.id.menuWatchOnlyWallet) {
            startActivity(new Intent(this, wallet.tools.WatchOnlyWalletActivity.class));
            return true;
        } else if (itemId == R.id.menuPaperWallet) {
            startActivity(new Intent(this, wallet.tools.PaperWalletActivity.class));
            return true;
        } else if (itemId == R.id.menuAbout) {
            startActivity(new Intent(this, wallet.about.AboutActivity.class));
            return true;
        } else if (itemId == R.id.menuSync) {
            startActivity(new Intent(this, wallet.main.SyncActivity.class));
            return true;
        } else if (itemId == R.id.menuBackupRecovery) {
            startActivity(new Intent(this, wallet.backup.BackupRecoveryActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openScanner() {
        ScanOptions options = new ScanOptions()
                .setPrompt(getString(R.string.scan_bitcoin_address));
        barcodeLauncher.launch(options);
    }

    private void handleScannedAddress(String value) {
        String address = value.trim();
        if (TextUtils.isEmpty(address)) {
            return;
        }
        Intent send = new Intent(this, wallet.send.SendActivity.class);
        send.putExtra(wallet.send.SendActivity.EXTRA_RECIPIENT, address);
        startActivity(send);
    }

    private void copyAddress() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            showToastMessage(getString(R.string.wallet_address_missing));
            return;
        }

        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(
                getString(R.string.bitcoin_address_clip_label), address));
        showToastMessage(getString(R.string.address_copied));
    }

    private void exportTransactions() {
        if (allTransactions.isEmpty()) {
            showToastMessage(getString(R.string.no_transactions_to_export));
            return;
        }
        transactionExportLauncher.launch("bitcoin-transactions.csv");
    }

    private void showAddressTools() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            showToastMessage(getString(R.string.wallet_address_missing));
            return;
        }

        View content = getLayoutInflater().inflate(R.layout.dialog_address_tools, null);
        TextView type = content.findViewById(R.id.addressToolsType);
        TextView addressValue = content.findViewById(R.id.addressToolsAddress);
        TextViewUtils.setTextIfChanged(type, walletTypeText.getText());
        TextViewUtils.configureSelectableMiddleEllipsis(addressValue);
        TextViewUtils.setTextIfChanged(addressValue, address);

        new AlertDialog.Builder(this)
                .setTitle(R.string.address_tools_title)
                .setView(content)
                .setPositiveButton(R.string.copy_address, (dialog, which) -> copyAddress())
                .setNeutralButton(R.string.receive_qr_title, (dialog, which) -> showReceiveQr())
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void showWalletSelector() {
        final org.bitcoinj.wallet.Wallet wallet =
                MainActivityPresenter.getActiveWallet();
        if (wallet == null) {
            showToastMessage(getString(R.string.wallet_not_ready));
            return;
        }

        try {
            class Entry {
                final int kind; final String address;
                Entry(int kind, String address) { this.kind = kind; this.address = address; }
            }
            java.util.List<Entry> entries = new java.util.ArrayList<>();
            entries.add(new Entry(0, null));
            for (String address : WalletSelection.getImportedAddresses(this, wallet)) {
                entries.add(new Entry(1, address));
            }
            for (org.bitcoinj.script.Script script : wallet.getWatchedScripts()) {
                try {
                    entries.add(new Entry(2, script.getToAddress(wallet.getParams()).toString()));
                } catch (Exception ignored) {
                    // Ignore an individual malformed watch script.
                }
            }

            String selectedWatch = WalletSelection.getSelectedWatchAddress(this);
            String selectedImported = WalletSelection.getSelectedImportedAddress(this);
            int checked = 0;
            for (int i = 1; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if ((e.kind == 1 && e.address.equals(selectedImported))
                        || (e.kind == 2 && e.address.equals(selectedWatch))) {
                    checked = i; break;
                }
            }

            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            int horizontal = dp(8);
            container.setPadding(horizontal, 0, horizontal, dp(4));
            final AlertDialog[] dialogHolder = new AlertDialog[1];

            for (int i = 0; i < entries.size(); i++) {
                final int position = i; final Entry entry = entries.get(i);
                View row = getLayoutInflater().inflate(R.layout.item_wallet_selector, container, false);
                android.widget.RadioButton radio = row.findViewById(R.id.walletSelectorRadio);
                TextView type = row.findViewById(R.id.walletSelectorType);
                TextView addressView = row.findViewById(R.id.walletSelectorAddress);
                TextViewUtils.configureSelectableMiddleEllipsis(addressView);
                radio.setChecked(position == checked);
                if (entry.kind == 0) {
                    type.setText(R.string.wallet_type_main);
                    TextViewUtils.setTextIfChanged(addressView, wallet.currentReceiveAddress().toString());
                } else if (entry.kind == 1) {
                    type.setText(getString(R.string.wallet_type_imported) + " • "
                            + WalletAddressType.label(this, ImportedWalletStore.getAddressType(this, entry.address)));
                    TextViewUtils.setTextIfChanged(addressView, entry.address);
                } else {
                    type.setText(R.string.wallet_type_watch);
                    TextViewUtils.setTextIfChanged(addressView, entry.address);
                }
                row.setOnClickListener(v -> {
                    if (entry.kind == 0) WalletSelection.selectMain(this);
                    else if (entry.kind == 1) WalletSelection.selectImportedAddress(this, entry.address);
                    else WalletSelection.selectWatchAddress(this, entry.address);
                    BalanceWidgetProvider.requestRefresh(this);
                    dialogHolder[0].dismiss();
                    if (presenter != null) presenter.refresh();
                });
                container.addView(row);
                if (i < entries.size() - 1) {
                    View divider = new View(this);
                    divider.setBackgroundResource(R.drawable.bg_divider);
                    boolean groupBoundary = entry.kind != entries.get(i + 1).kind;
                    LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(groupBoundary ? 2 : 1));
                    dividerParams.setMargins(dp(12), dp(4), dp(12), dp(4));
                    container.addView(divider, dividerParams);
                }
            }
            dialogHolder[0] = new AlertDialog.Builder(this)
                    .setTitle(R.string.wallet_selector_title)
                    .setView(container)
                    .setNegativeButton(R.string.close, null)
                    .create();
            dialogHolder[0].show();
        } catch (Exception error) {
            showToastMessage(getString(R.string.wallet_selector_failed, error.getMessage()));
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showStartupSplash() {
        if (startupSplash == null) {
            return;
        }
        startupSplash.setVisibility(View.VISIBLE);
        startupSplash.postDelayed(() -> startupSplash.setVisibility(View.GONE), 900L);
    }

    private void showReceiveQr() {
        String address = addressText.getText().toString().trim();
        if (TextUtils.isEmpty(address) || address.equals(getString(R.string.loading))) {
            showToastMessage(getString(R.string.wallet_address_missing));
            return;
        }

        ReceiveQrDialog.show(this, address);
    }

    @Override
    public void setPresenter(MainActivityContract.MainActivityPresenter presenter) {
        this.presenter = presenter;
    }

    @Override
    public void displayDownloadContent(boolean shown) {
        // Sync is a background service concern. The wallet screen remains visible.
    }

    @Override
    public void displayProgress(int percent) {
        // Sync progress is shown on the Sync page and in the system notification.
    }

    @Override
    public void displayPercentage(int percent) {
        // Sync progress is shown on the Sync page and in the system notification.
    }

    @Override
    public void displayMyBalance(String balance) {
        balanceText.setText(balance);
    }

    @Override
    public void displayBalanceState(String available, String pending) {
        availableBalanceText.setText(available);
        pendingBalanceText.setText(pending);
    }

    @Override
    public void displayWalletType(String type) {
        walletTypeText.setText(type);
    }

    @Override
    public void displayMyAddress(String address) {
        if (TextUtils.isEmpty(address)) {
            return;
        }

        TextViewUtils.setTextIfChanged(addressText, address);
        updateReceiveQr(address);

        if (swipeRefresh.isRefreshing()) {
            swipeRefresh.setRefreshing(false);
        }
    }

    private void updateReceiveQr(String address) {
        if (TextUtils.isEmpty(address) || receiveQr == null) {
            return;
        }
        if (address.equals(lastReceiveQrAddress)) {
            return;
        }

        lastReceiveQrAddress = address;
        final int generation = ++receiveQrGeneration;
        new Thread(() -> {
            try {
                final android.graphics.Bitmap qr = QrCodeGenerator.generate(address, 300);
                runOnUiThread(() -> {
                    if (!isFinishing() && generation == receiveQrGeneration) {
                        receiveQr.setImageBitmap(qr);
                    }
                });
            } catch (Exception ignored) {
                // QR display failure does not affect wallet operation.
            }
        }, "receive-qr-generator").start();
    }

    @Override
    public void displayTransactions(List<TransactionItem> transactions) {
        allTransactions.clear();
        if (transactions != null) {
            allTransactions.addAll(transactions);
        }
        applyTransactionFilter();
    }

    @Override
    public Context getActivityContext() {
        return this;
    }

    @Override
    public void showToastMessage(String message) {
        runOnUiThread(() ->
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }


    private void exportTransactionsTo(android.net.Uri destination) {
        new Thread(() -> {
            try (java.io.OutputStream output = getContentResolver().openOutputStream(destination);
                 java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8)) {
                if (output == null) throw new java.io.IOException(getString(R.string.export_destination_open_failed));
                writer.write("type,amount,time,confirmations,state,peers,txid\n");
                for (TransactionItem item : allTransactions) {
                    writer.write(csv(item.type)); writer.write(",");
                    writer.write(csv(item.amount)); writer.write(",");
                    writer.write(csv(item.time)); writer.write(",");
                    writer.write(csv(item.confirmations)); writer.write(",");
                    writer.write(csv(item.state)); writer.write(",");
                    writer.write(csv(item.peers)); writer.write(",");
                    writer.write(csv(item.txid)); writer.write("\n");
                }
                writer.flush();
                runOnUiThread(() -> showToastMessage(getString(R.string.export_transactions_success)));
            } catch (Exception error) {
                runOnUiThread(() -> showToastMessage(getString(R.string.export_transactions_failed,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())));
            }
        }, "transaction-export").start();
    }

    private String csv(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_TRANSACTION_FILTER, transactionFilter);
        outState.putInt(STATE_TRANSACTION_LIMIT, transactionLimit);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (presenter != null) {
            // The foreground sync service owns WalletAppKit. Destroying this
            // Activity must only detach the UI, not stop blockchain sync.
            presenter.detachView();
        }
    }
}
