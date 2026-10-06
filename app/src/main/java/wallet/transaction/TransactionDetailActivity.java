package wallet.transaction;

import wallet.main.BaseActivity;

import androidx.appcompat.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Coin;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.Wallet;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.main.WalletSelection;

import wallet.security.WalletSecurity;
import wallet.ui.TextViewUtils;

/** Simple wallet-style transaction detail. Heavy work stays off the UI thread. */
public final class TransactionDetailActivity extends BaseActivity {

    public static final String EXTRA_TXID = "txid";

    private String txid;
    private TextView transactionType;
    private TextView transactionAmount;
    private TextView transactionFrom;
    private TextView transactionTo;
    private LinearLayout transactionDetailsRows;
    private LinearLayout sentDetailsCard;
    private LinearLayout sentDetailsRows;
    private LinearLayout receivedDetailsCard;
    private LinearLayout receivedDetailsRows;
    private TextView transactionIdValue;
    private TextView sentDetailsTitle;
    private TextView receivedDetailsTitle;
    private Button boostFeeButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_transaction_detail);

        Toolbar toolbar = findViewById(R.id.toolbar_transaction_detail);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.transaction_detail_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        transactionType = findViewById(R.id.transactionType);
        transactionAmount = findViewById(R.id.transactionAmount);
        transactionFrom = findViewById(R.id.transactionFrom);
        TextViewUtils.configureSelectableMiddleEllipsis(transactionFrom);
        transactionTo = findViewById(R.id.transactionTo);
        TextViewUtils.configureSelectableMiddleEllipsis(transactionTo);
        transactionDetailsRows = findViewById(R.id.transactionDetailsRows);
        sentDetailsCard = findViewById(R.id.sentDetailsCard);
        sentDetailsRows = findViewById(R.id.sentDetailsRows);
        sentDetailsTitle = findViewById(R.id.sentDetailsTitle);
        receivedDetailsCard = findViewById(R.id.receivedDetailsCard);
        receivedDetailsRows = findViewById(R.id.receivedDetailsRows);
        receivedDetailsTitle = findViewById(R.id.receivedDetailsTitle);
        transactionIdValue = findViewById(R.id.transactionIdValue);
        TextViewUtils.configureSelectableMiddleEllipsis(transactionIdValue);

        Button copy = findViewById(R.id.copyTransactionIdButton);
        copy.setOnClickListener(v -> copyTxid());

        boostFeeButton = findViewById(R.id.boostFeeButton);
        boostFeeButton.setOnClickListener(v -> showBoostFeeDialog());

        txid = getIntent().getStringExtra(EXTRA_TXID);
        loadTransaction();
    }

    /** Find the transaction once, then prepare everything once in the worker thread. */
    private void loadTransaction() {
        if (TextUtils.isEmpty(txid)) {
            showUnavailable(R.string.transaction_detail_unavailable);
            return;
        }

        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) {
            showUnavailable(R.string.wallet_not_ready);
            return;
        }

        new Thread(() -> {
            try {
                Wallet wallet = kit.wallet();
                Transaction transaction = findTransaction(wallet, Sha256Hash.wrap(txid));
                DetailData data = transaction == null ? null : prepareDetail(transaction, wallet);
                runOnUiThread(() -> render(data));
            } catch (Exception error) {
                String message = error.getMessage();
                runOnUiThread(() -> showUnavailable(getString(
                        R.string.transaction_detail_failed,
                        message == null ? error.getClass().getSimpleName() : message)));
            }
        }, "transaction-detail").start();
    }

    private Transaction findTransaction(Wallet wallet, Sha256Hash wanted) {
        for (Transaction transaction : wallet.getTransactions(true)) {
            if (wanted.equals(transaction.getTxId())) {
                return transaction;
            }
        }
        return null;
    }

    private DetailData prepareDetail(Transaction transaction, Wallet wallet) {
        Script selectedWatchScript = WalletSelection.findSelectedScript(this, wallet);

        Coin received = selectedWatchScript == null
                ? safeValueSentToMe(transaction, wallet)
                : sumWalletOutputs(transaction, wallet, selectedWatchScript);
        Coin sent = selectedWatchScript == null
                ? safeValueSentFromMe(transaction, wallet)
                : sumWatchedInputs(transaction, selectedWatchScript);

        Coin net = received.subtract(sent);
        boolean isReceived = net.isPositive();

        List<TxEntry> sentEntries;
        List<TxEntry> receivedEntries;

        if (isReceived) {
            // Incoming: show where the value came from, then the outputs
            // that actually belong to this wallet. The two cards therefore
            // answer two different questions instead of reusing "sent/received"
            // labels that can be mistaken for the wallet's net amount.
            sentEntries = collectAllInputEntries(transaction);
            receivedEntries = collectWalletOutputs(transaction, wallet, selectedWatchScript);
        } else if (net.isNegative()) {
            // Outgoing: show only the outputs sent to other parties in the
            // "Sent" card. Wallet-owned outputs are change and are shown in
            // the second card. This avoids presenting the gross input value
            // or the gross output value as the amount actually sent.
            sentEntries = collectExternalOutputs(transaction, wallet, selectedWatchScript);
            receivedEntries = collectWalletOutputs(transaction, wallet, selectedWatchScript);
        } else {
            // Self-transfer/neutral transaction: fall back to an explicit
            // transaction-wide input/output view rather than implying a
            // direction that is not present.
            sentEntries = collectAllInputEntries(transaction);
            receivedEntries = collectAllOutputEntries(transaction);
        }

        String from;
        String to;
        if (isReceived) {
            from = sentEntries.isEmpty()
                    ? getString(R.string.transaction_unknown_address)
                    : sentEntries.get(0).address;
            to = receivedEntries.isEmpty()
                    ? firstWalletOutput(transaction, wallet, selectedWatchScript)
                    : receivedEntries.get(0).address;
        } else if (net.isNegative()) {
            List<TxEntry> walletInputs = collectOwnInputs(
                    transaction, wallet, selectedWatchScript);
            from = walletInputs.isEmpty()
                    ? getString(R.string.transaction_unknown_address)
                    : walletInputs.get(0).address;
            to = sentEntries.isEmpty()
                    ? firstExternalOutput(transaction, wallet, selectedWatchScript)
                    : sentEntries.get(0).address;
        } else {
            from = sentEntries.isEmpty()
                    ? getString(R.string.transaction_unknown_address)
                    : sentEntries.get(0).address;
            to = receivedEntries.isEmpty()
                    ? getString(R.string.transaction_unknown_address)
                    : receivedEntries.get(0).address;
        }

        return new DetailData(
                isReceived,
                net,
                from,
                to,
                sentEntries,
                receivedEntries,
                buildTransactionRows(transaction),
                canBoostRbf(transaction, wallet, selectedWatchScript, net),
                transaction.getTxId().toString());
    }

    private void render(DetailData data) {
        if (data == null) {
            showUnavailable(R.string.transaction_detail_unavailable);
            return;
        }

        boolean isSent = data.net.isNegative();
        transactionType.setText(isSent
                ? R.string.transaction_sent
                : data.isReceived ? R.string.transaction_received : R.string.transaction_generic);
        transactionAmount.setText(formatSigned(data.net));

        setLabeledAddress(transactionFrom, R.string.transaction_from_label, data.from);
        setLabeledAddress(transactionTo, R.string.transaction_to_label, data.to);

        renderTransactionRows(data.detailRows);

        if (isSent) {
            sentDetailsTitle.setText(R.string.sent_details_title);
            receivedDetailsTitle.setText(R.string.change_details_title);
            renderEntries(sentDetailsRows, data.sentEntries, R.string.sent_recipients_total);
            renderEntries(receivedDetailsRows, data.receivedEntries, R.string.change_returned_total);
        } else if (data.isReceived) {
            sentDetailsTitle.setText(R.string.received_from_title);
            receivedDetailsTitle.setText(R.string.received_by_wallet_title);
            renderEntries(sentDetailsRows, data.sentEntries, R.string.received_from_total);
            renderEntries(receivedDetailsRows, data.receivedEntries, R.string.received_by_wallet_total);
        } else {
            sentDetailsTitle.setText(R.string.transaction_inputs_title);
            receivedDetailsTitle.setText(R.string.transaction_outputs_title);
            renderEntries(sentDetailsRows, data.sentEntries, R.string.transaction_total_from);
            renderEntries(receivedDetailsRows, data.receivedEntries, R.string.transaction_total_to);
        }

        sentDetailsCard.setVisibility(
                data.sentEntries.isEmpty() ? View.GONE : View.VISIBLE);
        receivedDetailsCard.setVisibility(
                data.receivedEntries.isEmpty() ? View.GONE : View.VISIBLE);
        TextViewUtils.setTextIfChanged(transactionIdValue, data.txid);
        boostFeeButton.setVisibility(data.canBoost ? View.VISIBLE : View.GONE);
        boostFeeButton.setEnabled(true);
    }

    private boolean canBoostRbf(
            Transaction transaction, Wallet wallet, Script selectedWatchScript, Coin net) {
        return selectedWatchScript == null
                && net != null
                && net.isNegative()
                && RbfBumpService.canBump(transaction, wallet);
    }

    private void showBoostFeeDialog() {
        if (TextUtils.isEmpty(txid)) return;
        WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
        if (kit == null) {
            Toast.makeText(this, R.string.wallet_not_ready, Toast.LENGTH_SHORT).show();
            return;
        }

        View box = getLayoutInflater().inflate(R.layout.dialog_rbf_fee, null);
        EditText input = box.findViewById(R.id.rbfBoostFeeInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.rbf_boost_title)
                .setMessage(R.string.rbf_boost_description)
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.rbf_boost_action, null)
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String text = input.getText().toString().trim();
                    final double parsed;
                    try {
                        parsed = Double.parseDouble(text);
                        if (!Double.isFinite(parsed) || parsed <= 0.0 || parsed > 100000.0) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException error) {
                        input.setError(getString(R.string.rbf_boost_invalid_fee));
                        return;
                    }
                    dialog.dismiss();
                    boostFee(parsed);
                }));
        dialog.show();
    }

    /** Electrum-style RBF: preserve payment outputs, reduce wallet outputs first, then add confirmed inputs. */
    private void boostFee(double targetFeeRateSatVb) {
        boostFeeButton.setEnabled(false);
        new Thread(() -> {
            try {
                WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
                NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
                if (kit == null || parameters == null) {
                    throw new IllegalStateException(getString(R.string.wallet_not_ready));
                }

                Transaction replacement;
                String resultTxid;
                Wallet wallet = kit.wallet();
                synchronized (wallet) {
                    if (wallet.isWatching()) {
                        throw new IllegalStateException(getString(R.string.rbf_boost_watch_only));
                    }
                    if (wallet.isEncrypted() && !WalletSecurity.isSessionValid(wallet)) {
                        throw new IllegalStateException(getString(R.string.wallet_locked));
                    }

                    Transaction original = wallet.getTransaction(Sha256Hash.wrap(txid));
                    if (original == null) {
                        throw new IllegalStateException(getString(R.string.rbf_boost_unavailable));
                    }
                    try {
                        replacement = RbfBumpService.bump(
                                wallet, original, targetFeeRateSatVb, WalletSecurity.getSessionKey());
                    } catch (RbfBumpService.RbfException error) {
                        throw new IllegalStateException(rbfErrorMessage(error.error()), error);
                    }

                    resultTxid = replacement.getTxId().toString();
                    wallet.commitTx(replacement);
                    org.bitcoinj.core.TransactionBroadcast broadcast =
                            kit.peerGroup().broadcastTransaction(replacement);
                    broadcast.broadcast();
                    try {
                        broadcast.awaitRelayed().get(15, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (java.util.concurrent.TimeoutException ignored) {
                        // Relay confirmation is advisory; broadcast already occurred.
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }

                final String newTxid = resultTxid;
                runOnUiThread(() -> {
                    txid = newTxid;
                    Toast.makeText(this, R.string.rbf_boost_success, Toast.LENGTH_LONG).show();
                    loadTransaction();
                });
            } catch (Exception error) {
                String message = error.getCause() instanceof Exception
                        ? error.getCause().getMessage()
                        : error.getMessage();
                runOnUiThread(() -> {
                    boostFeeButton.setEnabled(true);
                    Toast.makeText(this,
                            message == null || message.isEmpty()
                                    ? getString(R.string.rbf_boost_failed)
                                    : message,
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "rbf-boost").start();
    }

    private String rbfErrorMessage(RbfBumpService.Error error) {
        switch (error) {
            case FEE_NOT_HIGHER:
                return getString(R.string.rbf_boost_fee_not_higher);
            case FEE_TARGET_NOT_MET:
                return getString(R.string.rbf_boost_fee_too_low);
            case NO_PAYMENT_OUTPUTS:
                return getString(R.string.rbf_boost_no_payment_outputs);
            case NO_CHANGE_OR_FUNDS:
                return getString(R.string.rbf_boost_insufficient_funds);
            case WATCH_ONLY:
                return getString(R.string.rbf_boost_watch_only);
            case MISSING_INPUT:
                return getString(R.string.rbf_boost_inputs_unavailable);
            case UNAVAILABLE:
                return getString(R.string.rbf_boost_unavailable);
            case FAILED:
            default:
                return getString(R.string.rbf_boost_failed);
        }
    }

    private List<RowData> buildTransactionRows(Transaction transaction) {
        List<RowData> rows = new ArrayList<>();
        TransactionConfidence confidence = transaction.getConfidence();
        int depth = confidence == null ? 0 : confidence.getDepthInBlocks();

        rows.add(new RowData(getString(R.string.transaction_status_label), status(transaction)));

        Coin fee = safeFee(transaction);
        rows.add(new RowData(
                getString(R.string.transaction_fee_label),
                fee == null ? "—" : fee.toFriendlyString()));

        // Serialization happens here, on the worker thread, never while drawing the screen.
        int size = transaction.bitcoinSerialize().length;
        long weight = getWeightUnits(transaction, size);
        long vbytes = (weight + 3L) / 4L;
        long feeRate = fee == null || vbytes <= 0L ? 0L : Math.max(0L, fee.value / vbytes);
        rows.add(new RowData(
                getString(R.string.transaction_size_weight_label),
                getString(R.string.transaction_size_weight, size, weight, feeRate)));

        int appearedHeight = getAppearedHeight(transaction);
        MainActivityPresenter presenter = MainActivityPresenter.getActivePresenter();
        int bestHeight = presenter == null ? 0 : presenter.getBestChainHeight();
        String confirmations = depth > 0 && appearedHeight > 0 && bestHeight > 0
                ? getString(R.string.transaction_height_pair, depth, appearedHeight, bestHeight)
                : String.valueOf(depth);
        rows.add(new RowData(getString(R.string.transaction_confirmations_label), confirmations));
        rows.add(new RowData(getString(R.string.transaction_time_label), formatTime(transaction)));
        rows.add(new RowData(getString(R.string.transaction_current_time_label), formatNow()));
        rows.add(new RowData(getString(R.string.transaction_age_label), formatAge(transaction)));
        rows.add(new RowData(getString(R.string.transaction_version_label), String.valueOf(transaction.getVersion())));
        return rows;
    }

    /**
     * Keeps the label visually secondary while the actual address remains the
     * primary content of the row. Both colors come from the active Android
     * theme so light/dark mode stays consistent with the rest of the screen.
     */
    private void setLabeledAddress(TextView view, int labelResId, String value) {
        String label = getString(labelResId) + ": ";
        String text = label + (value == null ? "" : value);
        SpannableString styled = new SpannableString(text);
        styled.setSpan(
                new ForegroundColorSpan(resolveThemeColor(android.R.attr.textColorSecondary)),
                0,
                label.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(styled);
    }

    private int resolveThemeColor(int attr) {
        TypedArray attributes = obtainStyledAttributes(new int[] {attr});
        try {
            ColorStateList colors = attributes.getColorStateList(0);
            if (colors != null) {
                return colors.getColorForState(
                        transactionFrom == null
                                ? getWindow().getDecorView().getDrawableState()
                                : transactionFrom.getDrawableState(),
                        colors.getDefaultColor());
            }
            return attributes.getColor(0, android.graphics.Color.TRANSPARENT);
        } finally {
            attributes.recycle();
        }
    }

    private void renderTransactionRows(List<RowData> rows) {
        transactionDetailsRows.removeAllViews();
        for (RowData row : rows) {
            addRow(row.label, row.value);
        }
    }

    private void renderEntries(LinearLayout container, List<TxEntry> entries, int totalString) {
        container.removeAllViews();
        Coin total = sum(entries);

        TextView summary = (TextView) getLayoutInflater().inflate(
                R.layout.item_transaction_summary, container, false);
        summary.setText(getString(totalString, total.toFriendlyString(), entries.size()));
        container.addView(summary);

        for (TxEntry entry : entries) {
            View row = getLayoutInflater().inflate(
                    R.layout.item_transaction_entry, container, false);
            TextView address = row.findViewById(R.id.transactionEntryAddress);
            TextViewUtils.configureSelectableMiddleEllipsis(address);
            TextView amount = row.findViewById(R.id.transactionEntryAmount);
            TextViewUtils.setTextIfChanged(address, entry.address + " (" + entry.type + ")");
            amount.setText(entry.amount.toFriendlyString());
            container.addView(row);
        }
    }

    private List<TxEntry> collectOwnInputs(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionInput input : transaction.getInputs()) {
            TransactionOutput output = connectedOutput(input);
            if (output == null || !belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                continue;
            }
            result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
        }
        return result;
    }

    private List<TxEntry> collectAllInputEntries(Transaction transaction) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionInput input : transaction.getInputs()) {
            TransactionOutput output = connectedOutput(input);
            if (output != null) {
                result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
                continue;
            }

            TxEntry simpleSender = senderFromInput(input);
            if (simpleSender != null) {
                result.add(simpleSender);
            } else {
                Coin value = input.getValue();
                if (value != null && !value.isZero()) {
                    result.add(new TxEntry(
                            getString(R.string.transaction_unknown_address),
                            getString(R.string.transaction_type_unknown),
                            value));
                }
            }
        }
        return result;
    }

    private TxEntry senderFromInput(TransactionInput input) {
        try {
            Method method = input.getClass().getMethod("getFromAddress");
            Object value = method.invoke(input);
            if (value != null) {
                Coin amount = input.getValue();
                if (amount != null && !amount.isZero()) {
                    return new TxEntry(value.toString(), getString(R.string.transaction_type_input), amount);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private List<TxEntry> collectAllOutputEntries(Transaction transaction) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionOutput output : transaction.getOutputs()) {
            result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
        }
        return result;
    }

    private List<TxEntry> collectExternalOutputs(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionOutput output : transaction.getOutputs()) {
            if (!belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
            }
        }
        return result;
    }

    private List<TxEntry> collectWalletOutputs(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        List<TxEntry> result = new ArrayList<>();
        for (TransactionOutput output : transaction.getOutputs()) {
            if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                result.add(new TxEntry(addressOf(output), scriptType(output), output.getValue()));
            }
        }
        return result;
    }

    private Coin sumWalletOutputs(Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        Coin total = Coin.ZERO;
        for (TransactionOutput output : transaction.getOutputs()) {
            if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                total = total.add(output.getValue());
            }
        }
        return total;
    }

    private Coin sumWatchedInputs(Transaction transaction, Script selectedWatchScript) {
        Coin total = Coin.ZERO;
        for (TransactionInput input : transaction.getInputs()) {
            TransactionOutput output = connectedOutput(input);
            if (output != null && selectedWatchScript.equals(output.getScriptPubKey())) {
                total = total.add(output.getValue());
            }
        }
        return total;
    }

    private boolean belongsToSelectedWallet(
            TransactionOutput output, Wallet wallet, Script selectedWatchScript) {
        if (selectedWatchScript != null) {
            return selectedWatchScript.equals(output.getScriptPubKey());
        }
        return output.isMine(wallet) && !WalletSelection.isWatchedOutput(wallet, output);
    }

    private TransactionOutput connectedOutput(TransactionInput input) {
        if (input == null) {
            return null;
        }
        try {
            return input.getConnectedOutput();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstExternalOutput(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        for (TransactionOutput output : transaction.getOutputs()) {
            if (!belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                return addressOf(output);
            }
        }
        return getString(R.string.transaction_unknown_address);
    }

    private String firstWalletOutput(
            Transaction transaction, Wallet wallet, Script selectedWatchScript) {
        for (TransactionOutput output : transaction.getOutputs()) {
            if (belongsToSelectedWallet(output, wallet, selectedWatchScript)) {
                return addressOf(output);
            }
        }
        return getString(R.string.transaction_unknown_address);
    }

    private String addressOf(TransactionOutput output) {
        try {
            return output.getScriptPubKey()
                    .getToAddress(MainActivityPresenter.getActiveParameters())
                    .toString();
        } catch (Exception ignored) {
            return getString(R.string.transaction_unknown_address);
        }
    }

    private String scriptType(TransactionOutput output) {
        Object type = output.getScriptPubKey().getScriptType();
        return type == null ? getString(R.string.transaction_type_unknown) : type.toString();
    }

    private Coin safeValueSentFromMe(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValueSentFromMe(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
    }

    private Coin safeValueSentToMe(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValueSentToMe(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
    }

    private Coin sum(List<TxEntry> entries) {
        Coin total = Coin.ZERO;
        for (TxEntry entry : entries) {
            total = total.add(entry.amount);
        }
        return total;
    }

    private String formatSigned(Coin net) {
        if (net.isPositive()) return "+" + net.toFriendlyString();
        return net.toFriendlyString();
    }

    private Coin safeFee(Transaction transaction) {
        try {
            return transaction.getFee();
        } catch (Exception ignored) {
            return null;
        }
    }

    private long getWeightUnits(Transaction transaction, int size) {
        try {
            Method method = transaction.getClass().getMethod("getWeight");
            Object value = method.invoke(transaction);
            if (value instanceof Number) return ((Number) value).longValue();
        } catch (Exception ignored) {
        }
        return size * 4L;
    }

    private int getAppearedHeight(Transaction transaction) {
        try {
            TransactionConfidence confidence = transaction.getConfidence();
            if (confidence == null) return 0;
            Method method = confidence.getClass().getMethod("getAppearedAtChainHeight");
            Object value = method.invoke(confidence);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String status(Transaction transaction) {
        TransactionConfidence confidence = transaction.getConfidence();
        if (confidence != null && confidence.getDepthInBlocks() > 0) {
            return getString(R.string.transaction_state_confirmed);
        }
        if (confidence != null
                && confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.DEAD) {
            return getString(R.string.transaction_state_dead);
        }
        return getString(R.string.transaction_state_pending);
    }

    private String formatTime(Transaction transaction) {
        if (!transaction.updateTime().isPresent()) return getString(R.string.unknown_time);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(Date.from(transaction.updateTime().get()));
    }

    private String formatNow() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private String formatAge(Transaction transaction) {
        if (!transaction.updateTime().isPresent()) return "—";
        long age = Math.max(0L, System.currentTimeMillis()
                - transaction.updateTime().get().toEpochMilli());
        long seconds = age / 1000L;
        if (seconds < 60L) return getString(R.string.transaction_age_seconds, seconds);
        if (seconds < 3600L) {
            return getString(
                    R.string.transaction_age_minutes,
                    seconds / 60L,
                    seconds % 60L);
        }
        if (seconds < 86400L) {
            return getString(
                    R.string.transaction_age_hours,
                    seconds / 3600L,
                    (seconds % 3600L) / 60L,
                    seconds % 60L);
        }
        return getString(
                R.string.transaction_age_days,
                seconds / 86400L,
                (seconds % 86400L) / 3600L,
                (seconds % 3600L) / 60L,
                seconds % 60L);
    }

    private void addRow(String label, String value) {
        View row = getLayoutInflater().inflate(
                R.layout.item_transaction_detail_row, transactionDetailsRows, false);
        ((TextView) row.findViewById(R.id.transactionRowLabel)).setText(label);
        TextView rowValue = row.findViewById(R.id.transactionRowValue);
        TextViewUtils.configureSelectableMiddleEllipsis(rowValue);
        TextViewUtils.setTextIfChanged(rowValue, value);
        transactionDetailsRows.addView(row);
    }

    private void showUnavailable(String text) {
        transactionType.setText(R.string.transaction_detail_title);
        transactionAmount.setText(text);
        TextViewUtils.setTextIfChanged(transactionFrom, "");
        TextViewUtils.setTextIfChanged(transactionTo, "");
        transactionDetailsRows.removeAllViews();
        sentDetailsCard.setVisibility(View.GONE);
        receivedDetailsCard.setVisibility(View.GONE);
        TextViewUtils.setTextIfChanged(transactionIdValue, txid == null ? "—" : txid);
    }

    private void showUnavailable(int resource) {
        showUnavailable(getString(resource));
    }

    private void copyTxid() {
        if (TextUtils.isEmpty(txid)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("TXID", txid));
        Toast.makeText(this, R.string.transaction_id_copied, Toast.LENGTH_SHORT).show();
    }

    private static final class RowData {
        final String label;
        final String value;
        RowData(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private static final class DetailData {
        final boolean isReceived;
        final Coin net;
        final String from;
        final String to;
        final List<TxEntry> sentEntries;
        final List<TxEntry> receivedEntries;
        final List<RowData> detailRows;
        final boolean canBoost;
        final String txid;

        DetailData(boolean isReceived, Coin net, String from, String to,
                   List<TxEntry> sentEntries, List<TxEntry> receivedEntries,
                   List<RowData> detailRows, boolean canBoost, String txid) {
            this.isReceived = isReceived;
            this.net = net;
            this.from = from;
            this.to = to;
            this.sentEntries = sentEntries;
            this.receivedEntries = receivedEntries;
            this.detailRows = detailRows;
            this.canBoost = canBoost;
            this.txid = txid;
        }
    }

    private static final class TxEntry {
        final String address;
        final String type;
        final Coin amount;

        TxEntry(String address, String type, Coin amount) {
            this.address = address;
            this.type = type;
            this.amount = amount;
        }
    }

}
