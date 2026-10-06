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
import org.bitcoinj.base.Address;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.Wallet;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.main.WalletSelection;
import org.bitcoinj.wallet.SendRequest;

import wallet.security.WalletSecurity;
import wallet.transaction.RbfMetadata;
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
        if (transaction == null || wallet == null || selectedWatchScript != null
                || !net.isNegative() || !transaction.isPending()
                || !transaction.isOptInFullRBF() || safeFee(transaction) == null) {
            return false;
        }

        int changeIndex = RbfMetadata.getChangeIndex(this, transaction.getTxId().toString());
        if (changeIndex >= 0 && changeIndex < transaction.getOutputs().size()) {
            TransactionOutput change = transaction.getOutput(changeIndex);
            if (change.isMine(wallet) && !WalletSelection.isWatchedOutput(wallet, change)) {
                return true;
            }
        }

        for (TransactionOutput output : transaction.getOutputs()) {
            if (!output.isMine(wallet) && !WalletSelection.isWatchedOutput(wallet, output)) {
                return true;
            }
        }
        return false;
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
                    double parsed;
                    try {
                        parsed = Double.parseDouble(text);
                        if (!Double.isFinite(parsed) || parsed <= 0 || parsed > 100000) {
                            throw new NumberFormatException();
                        }
                    } catch (NumberFormatException error) {
                        input.setError(getString(R.string.rbf_boost_invalid_fee));
                        return;
                    }
                    int feeRate = (int) Math.ceil(parsed);
                    dialog.dismiss();
                    boostFee(feeRate);
                }));
        dialog.show();
    }

    private void boostFee(int targetFeeRateSatVb) {
        boostFeeButton.setEnabled(false);
        new Thread(() -> {
            try {
                WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
                NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
                if (kit == null || parameters == null) {
                    throw new IllegalStateException(getString(R.string.wallet_not_ready));
                }

                Wallet wallet = kit.wallet();
                String oldTxid;
                String newTxid;
                int newChangeIndex;

                synchronized (wallet) {
                    if (wallet.isWatching()) {
                        throw new IllegalStateException(getString(R.string.rbf_boost_watch_only));
                    }
                    if (wallet.isEncrypted() && !WalletSecurity.isSessionValid(wallet)) {
                        throw new IllegalStateException(getString(R.string.wallet_locked));
                    }

                    Transaction original = wallet.getTransaction(Sha256Hash.wrap(txid));
                    if (original == null || !original.isPending() || !original.isOptInFullRBF()) {
                        throw new IllegalStateException(getString(R.string.rbf_boost_unavailable));
                    }

                    Coin oldFee = safeFee(original);
                    if (oldFee == null || oldFee.isNegative()) {
                        throw new IllegalStateException(getString(R.string.transaction_fee_unavailable));
                    }

                    long oldVbytes = Math.max(1L, original.getVsize());
                    long oldRate = (oldFee.value + oldVbytes - 1L) / oldVbytes;
                    if (targetFeeRateSatVb <= oldRate) {
                        throw new IllegalStateException(getString(R.string.rbf_boost_fee_not_higher));
                    }

                    int changeIndex = RbfMetadata.getChangeIndex(this, original.getTxId().toString());
                    if (!isUsableChangeOutput(original, wallet, changeIndex)) {
                        changeIndex = -1;
                    }

                    RbfReplacement result = buildRbfReplacement(
                            original, wallet, parameters, changeIndex, targetFeeRateSatVb, oldFee);
                    Transaction replacement = result.transaction;
                    validateReplacement(original, replacement, wallet, changeIndex, targetFeeRateSatVb, oldFee);
                    replacement.setPurpose(Transaction.Purpose.RAISE_FEE);
                    Transaction.verify(parameters.network(), replacement);

                    oldTxid = original.getTxId().toString();
                    newTxid = replacement.getTxId().toString();
                    newChangeIndex = result.changeIndex;

                    wallet.commitTx(replacement);
                    RbfMetadata.recordReplacement(this, oldTxid, newTxid, newChangeIndex);

                    org.bitcoinj.core.TransactionBroadcast broadcast =
                            kit.peerGroup().broadcastTransaction(replacement);
                    broadcast.broadcast();
                    try {
                        broadcast.awaitRelayed().get(15, TimeUnit.SECONDS);
                    } catch (TimeoutException ignored) {
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }

                final String resultTxid = newTxid;
                runOnUiThread(() -> {
                    txid = resultTxid;
                    Toast.makeText(this, R.string.rbf_boost_success, Toast.LENGTH_LONG).show();
                    loadTransaction();
                });
            } catch (Exception error) {
                String message = error.getMessage();
                runOnUiThread(() -> {
                    boostFeeButton.setEnabled(true);
                    Toast.makeText(this,
                            getString(R.string.rbf_boost_failed,
                                    message == null ? error.getClass().getSimpleName() : message),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "fee-bump").start();
    }

    private boolean isUsableChangeOutput(
            Transaction transaction, Wallet wallet, int changeIndex) {
        if (changeIndex < 0 || changeIndex >= transaction.getOutputs().size()) {
            return false;
        }
        TransactionOutput output = transaction.getOutput(changeIndex);
        return output.isMine(wallet) && !WalletSelection.isWatchedOutput(wallet, output);
    }

    private RbfReplacement buildRbfReplacement(
            Transaction original,
            Wallet wallet,
            NetworkParameters parameters,
            int changeIndex,
            long targetFeeRateSatVb,
            Coin oldFee) {
        java.util.List<TransactionOutput> fixedOutputs = new java.util.ArrayList<>();
        for (int i = 0; i < original.getOutputs().size(); i++) {
            if (i != changeIndex) {
                fixedOutputs.add(original.getOutput(i).duplicateDetached());
            }
        }
        if (fixedOutputs.isEmpty()) {
            throw new IllegalStateException(getString(R.string.rbf_boost_outputs_changed));
        }

        java.util.List<TransactionOutput> candidates = collectReplacementCoins(wallet, original);
        java.util.List<TransactionOutput> selected = new java.util.ArrayList<>();
        byte[] oldChangeScript = changeIndex >= 0
                ? original.getOutput(changeIndex).getScriptBytes() : null;

        if (changeIndex < 0) {
            try {
                return buildReplacementWithAddedInputs(
                        original, fixedOutputs, selected, candidates, wallet, parameters,
                        null, targetFeeRateSatVb, oldFee, -1);
            } catch (IllegalStateException error) {
                return buildReplacementByReducingPayment(
                        original, wallet, parameters, targetFeeRateSatVb, oldFee);
            }
        }

        return buildReplacementWithAddedInputs(
                original, fixedOutputs, selected, candidates, wallet, parameters,
                oldChangeScript, targetFeeRateSatVb, oldFee, changeIndex);
    }

    private RbfReplacement buildReplacementWithAddedInputs(
            Transaction original,
            java.util.List<TransactionOutput> fixedOutputs,
            java.util.List<TransactionOutput> selected,
            java.util.List<TransactionOutput> candidates,
            Wallet wallet,
            NetworkParameters parameters,
            byte[] preferredChangeScript,
            long targetFeeRateSatVb,
            Coin oldFee,
            int originalChangeIndex) {
        byte[] changeScript = preferredChangeScript;

        for (;;) {
            if (changeScript == null && !selected.isEmpty()) {
                Address newChangeAddress = wallet.currentChangeAddress();
                changeScript = ScriptBuilder.createOutputScript(newChangeAddress).program();
            }
            Transaction estimate = assembleReplacement(
                    original, fixedOutputs, selected, wallet, parameters,
                    changeScript, Coin.SATOSHI, originalChangeIndex);
            signReplacement(wallet, estimate);

            long estimatedVbytes = Math.max(1L, estimate.getVsize());
            Coin targetFee = requiredFee(oldFee, estimatedVbytes, targetFeeRateSatVb);
            Coin spendable = totalInputValue(estimate).subtract(sumOutputs(fixedOutputs));
            Coin desiredChange = spendable.subtract(targetFee);

            if (desiredChange.isNegative()) {
                if (!appendNextCandidate(selected, candidates)) {
                    throw new IllegalStateException(getString(R.string.rbf_boost_insufficient_funds));
                }
                continue;
            }

            boolean includeChange = desiredChange.value >= estimateChangeDust(estimate, originalChangeIndex);
            Transaction replacement = assembleReplacement(
                    original, fixedOutputs, selected, wallet, parameters, changeScript,
                    includeChange ? desiredChange : null, originalChangeIndex);
            signReplacement(wallet, replacement);

            Coin actualFee = replacement.getFee();
            if (actualFee == null) {
                throw new IllegalStateException(getString(R.string.transaction_fee_unavailable));
            }
            long actualVbytes = Math.max(1L, replacement.getVsize());
            Coin minimumFee = requiredFee(oldFee, actualVbytes, targetFeeRateSatVb);

            if (actualFee.compareTo(minimumFee) < 0) {
                long delta = minimumFee.subtract(actualFee).value;
                if (includeChange && replacement.getOutputs().size() > fixedOutputs.size()) {
                    int changeIndex = originalChangeIndex >= 0
                            ? originalChangeIndex
                            : replacement.getOutputs().size() - 1;
                    TransactionOutput currentChange = replacement.getOutput(changeIndex);
                    Coin reduced = currentChange.getValue().subtract(Coin.valueOf(delta));
                    if (reduced.value >= currentChange.getMinNonDustValue().value) {
                        replacement = assembleReplacement(
                                original, fixedOutputs, selected, wallet, parameters, changeScript,
                                reduced, originalChangeIndex);
                        signReplacement(wallet, replacement);
                        actualFee = replacement.getFee();
                        actualVbytes = Math.max(1L, replacement.getVsize());
                        minimumFee = requiredFee(oldFee, actualVbytes, targetFeeRateSatVb);
                    }
                }
            }

            if (actualFee != null && actualFee.compareTo(minimumFee) >= 0
                    && actualFee.compareTo(oldFee) > 0) {
                int newChangeIndex = includeChange
                        ? replacement.getOutputs().size() - 1
                        : -1;
                if (originalChangeIndex >= 0 && includeChange) {
                    newChangeIndex = originalChangeIndex;
                }
                return new RbfReplacement(replacement, newChangeIndex);
            }

            if (!appendNextCandidate(selected, candidates)) {
                throw new IllegalStateException(getString(R.string.rbf_boost_insufficient_funds));
            }
        }
    }

    private RbfReplacement buildReplacementByReducingPayment(
            Transaction original,
            Wallet wallet,
            NetworkParameters parameters,
            long targetFeeRateSatVb,
            Coin oldFee) {
        java.util.List<TransactionOutput> outputs = new java.util.ArrayList<>();
        for (TransactionOutput output : original.getOutputs()) {
            if (output.isMine(wallet) || WalletSelection.isWatchedOutput(wallet, output)) {
                throw new IllegalStateException(getString(R.string.rbf_boost_insufficient_funds));
            }
            outputs.add(output.duplicateDetached());
        }
        if (outputs.isEmpty()) {
            throw new IllegalStateException(getString(R.string.rbf_boost_outputs_changed));
        }

        Transaction replacement = copyInputsAndOutputs(original, outputs, parameters, wallet);
        signReplacement(wallet, replacement);

        long vbytes = Math.max(1L, replacement.getVsize());
        Coin required = requiredFee(oldFee, vbytes, targetFeeRateSatVb);
        Coin delta = required.subtract(oldFee);
        if (!delta.isPositive()) {
            return new RbfReplacement(replacement, -1);
        }

        java.util.List<Integer> paymentIndexes = new java.util.ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            paymentIndexes.add(i);
        }
        paymentIndexes.sort((a, b) -> Integer.compare(
                replacement.getOutput(a).getScriptBytes().length,
                replacement.getOutput(b).getScriptBytes().length));

        long remaining = delta.value;
        for (Integer index : paymentIndexes) {
            TransactionOutput output = replacement.getOutput(index);
            long spendable = output.getValue().value - output.getMinNonDustValue().value;
            if (spendable <= 0) {
                continue;
            }
            long reduction = Math.min(spendable, remaining);
            Coin reduced = output.getValue().subtract(Coin.valueOf(reduction));
            replacement.replaceOutput(index, new TransactionOutput(
                    replacement, reduced, output.getScriptBytes()));
            remaining -= reduction;
            if (remaining == 0) {
                break;
            }
        }

        if (remaining > 0) {
            throw new IllegalStateException(getString(R.string.rbf_boost_insufficient_funds));
        }

        signReplacement(wallet, replacement);
        return new RbfReplacement(replacement, -1);
    }

    private Transaction copyInputsAndOutputs(
            Transaction original,
            java.util.List<TransactionOutput> outputs,
            NetworkParameters parameters,
            Wallet wallet) {
        Transaction copy = new Transaction(parameters);
        copy.setVersion((int) original.getVersion());
        copy.setLockTime(original.getLockTime());
        for (TransactionInput oldInput : original.getInputs()) {
            TransactionOutput connected = resolveConnectedOutput(oldInput, wallet);
            if (connected == null) {
                throw new IllegalStateException(getString(R.string.rbf_boost_inputs_unavailable));
            }
            TransactionInput input = new TransactionInput(
                    copy, new byte[0], oldInput.getOutpoint(), RBF_SEQUENCE,
                    oldInput.getValue(), null);
            input.connect(connected.duplicateDetached());
            copy.addInput(input);
        }
        for (TransactionOutput output : outputs) {
            copy.addOutput(output.duplicateDetached());
        }
        return copy;
    }

    private Coin requiredFee(Coin oldFee, long vbytes, long targetFeeRateSatVb) {
        long target = Math.multiplyExact(vbytes, targetFeeRateSatVb);
        long minimum = Math.addExact(oldFee.value, 1L);
        return Coin.valueOf(Math.max(target, minimum));
    }

    private long estimateChangeDust(Transaction transaction, int originalChangeIndex) {
        if (originalChangeIndex >= 0
                && originalChangeIndex < transaction.getOutputs().size()) {
            return transaction.getOutput(originalChangeIndex).getMinNonDustValue().value;
        }
        if (!transaction.getOutputs().isEmpty()) {
            return transaction.getOutput(transaction.getOutputs().size() - 1)
                    .getMinNonDustValue().value;
        }
        return Long.MAX_VALUE;
    }

    private void signReplacement(Wallet wallet, Transaction transaction) {
        SendRequest request = SendRequest.forTx(transaction);
        request.aesKey = WalletSecurity.getSessionKey();
        request.signInputs = true;
        wallet.signTransaction(request);
        if (!transaction.isOptInFullRBF()) {
            throw new IllegalStateException(getString(R.string.rbf_boost_failed));
        }
    }

    private java.util.List<TransactionOutput> collectReplacementCoins(
            Wallet wallet, Transaction original) {
        java.util.HashSet<String> originalOutpoints = new java.util.HashSet<>();
        for (TransactionInput input : original.getInputs()) {
            originalOutpoints.add(input.getOutpoint().toString());
        }

        java.util.List<TransactionOutput> candidates = new java.util.ArrayList<>();
        for (TransactionOutput candidate : wallet.getUnspents()) {
            if (candidate == null || !candidate.isAvailableForSpending()) continue;
            if (!candidate.isMine(wallet) || WalletSelection.isWatchedOutput(wallet, candidate)) continue;
            if (candidate.getParentTransaction() == null) continue;
            if (candidate.getParentTransaction().getConfidence() == null
                    || candidate.getParentTransaction().getConfidence().getDepthInBlocks() <= 0) continue;
            if (originalOutpoints.contains(candidate.getOutPointFor().toString())) continue;
            candidates.add(candidate);
        }
        candidates.sort((a, b) -> Long.compare(a.getValue().value, b.getValue().value));
        return candidates;
    }

    private boolean appendNextCandidate(
            java.util.List<TransactionOutput> selected,
            java.util.List<TransactionOutput> candidates) {
        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (TransactionOutput output : selected) {
            used.add(output.getOutPointFor().toString());
        }
        for (TransactionOutput candidate : candidates) {
            if (used.add(candidate.getOutPointFor().toString())) {
                selected.add(candidate);
                return true;
            }
        }
        return false;
    }

    private Transaction assembleReplacement(
            Transaction original,
            java.util.List<TransactionOutput> fixedOutputs,
            java.util.List<TransactionOutput> selected,
            Wallet wallet,
            NetworkParameters parameters,
            byte[] changeScript,
            Coin changeValue,
            int originalChangeIndex) {
        Transaction replacement = new Transaction(parameters);
        replacement.setVersion((int) original.getVersion());
        replacement.setLockTime(original.getLockTime());

        for (TransactionInput oldInput : original.getInputs()) {
            TransactionOutput connected = resolveConnectedOutput(oldInput, wallet);
            if (connected == null) {
                throw new IllegalStateException(getString(R.string.rbf_boost_inputs_unavailable));
            }
            TransactionInput input = new TransactionInput(
                    replacement, new byte[0], oldInput.getOutpoint(), RBF_SEQUENCE,
                    oldInput.getValue(), null);
            input.connect(connected.duplicateDetached());
            replacement.addInput(input);
        }

        for (TransactionOutput candidate : selected) {
            TransactionOutput parentOutput = candidate.getParentTransaction() == null
                    ? null
                    : candidate.getParentTransaction().getOutput(candidate.getIndex());
            if (parentOutput == null) {
                throw new IllegalStateException(getString(R.string.rbf_boost_inputs_unavailable));
            }
            TransactionInput input = new TransactionInput(
                    replacement, new byte[0], candidate.getOutPointFor(), RBF_SEQUENCE,
                    candidate.getValue(), null);
            input.connect(parentOutput.duplicateDetached());
            replacement.addInput(input);
        }

        if (originalChangeIndex >= 0) {
            for (int i = 0; i < original.getOutputs().size(); i++) {
                if (i == originalChangeIndex) {
                    if (changeValue != null) {
                        replacement.addOutput(new TransactionOutput(
                                replacement, changeValue, changeScript));
                    }
                } else {
                    replacement.addOutput(original.getOutput(i).duplicateDetached());
                }
            }
        } else {
            for (TransactionOutput output : fixedOutputs) {
                replacement.addOutput(output.duplicateDetached());
            }
            if (changeValue != null && changeScript != null) {
                replacement.addOutput(new TransactionOutput(
                        replacement, changeValue, changeScript));
            }
        }
        return replacement;
    }

    private TransactionOutput resolveConnectedOutput(
            TransactionInput input, Wallet wallet) {
        TransactionOutput connected = input.getConnectedOutput();
        if (connected != null) {
            return connected;
        }
        if (wallet == null) {
            WalletAppKit kit = MainActivityPresenter.getActiveWalletAppKit();
            wallet = kit == null ? null : kit.wallet();
        }
        if (wallet == null) {
            return null;
        }
        Transaction parent = wallet.getTransaction(input.getOutpoint().hash());
        if (parent == null) {
            return null;
        }
        int index = (int) input.getOutpoint().index();
        if (index < 0 || index >= parent.getOutputs().size()) {
            return null;
        }
        return parent.getOutput(index);
    }

    private Coin totalInputValue(Transaction transaction) {
        Coin total = Coin.ZERO;
        for (TransactionInput input : transaction.getInputs()) {
            Coin value = input.getValue();
            if (value == null) {
                throw new IllegalStateException(getString(R.string.rbf_boost_inputs_unavailable));
            }
            total = total.add(value);
        }
        return total;
    }

    private Coin sumOutputs(java.util.List<TransactionOutput> outputs) {
        Coin total = Coin.ZERO;
        for (TransactionOutput output : outputs) {
            total = total.add(output.getValue());
        }
        return total;
    }

    private void validateReplacement(
            Transaction original,
            Transaction replacement,
            Wallet wallet,
            int originalChangeIndex,
            long targetRate,
            Coin oldFee) {
        if (!original.isOptInFullRBF()) {
            throw new IllegalStateException(getString(R.string.rbf_boost_unavailable));
        }
        if (replacement.getInputs().size() < original.getInputs().size()) {
            throw new IllegalStateException(getString(R.string.rbf_boost_failed));
        }
        for (int i = 0; i < original.getInputs().size(); i++) {
            if (!original.getInput(i).getOutpoint().equals(replacement.getInput(i).getOutpoint())) {
                throw new IllegalStateException(getString(R.string.rbf_boost_failed));
            }
            if (replacement.getInput(i).getSequenceNumber() != RBF_SEQUENCE) {
                throw new IllegalStateException(getString(R.string.rbf_boost_failed));
            }
        }

        java.util.Map<String, Integer> requiredOutputs = new java.util.HashMap<>();
        for (int i = 0; i < original.getOutputs().size(); i++) {
            if (i == originalChangeIndex) {
                continue;
            }
            TransactionOutput output = original.getOutput(i);
            String key = outputKey(output);
            requiredOutputs.put(key, requiredOutputs.getOrDefault(key, 0) + 1);
        }

        java.util.Map<String, Integer> replacementOutputs = new java.util.HashMap<>();
        for (TransactionOutput output : replacement.getOutputs()) {
            String key = outputKey(output);
            replacementOutputs.put(key, replacementOutputs.getOrDefault(key, 0) + 1);
        }

        if (originalChangeIndex >= 0) {
            for (java.util.Map.Entry<String, Integer> entry : requiredOutputs.entrySet()) {
                int available = replacementOutputs.getOrDefault(entry.getKey(), 0);
                if (available < entry.getValue()) {
                    throw new IllegalStateException(getString(R.string.rbf_boost_outputs_changed));
                }
            }
        } else {
            validatePaymentOutputsForNoChange(original, replacement);
        }

        Coin newFee = safeFee(replacement);
        if (newFee == null || newFee.compareTo(oldFee) <= 0) {
            throw new IllegalStateException(getString(R.string.rbf_boost_fee_too_low));
        }
        long vbytes = Math.max(1L, replacement.getVsize());
        Coin minimumFee = requiredFee(oldFee, vbytes, targetRate);
        if (newFee.compareTo(minimumFee) < 0) {
            throw new IllegalStateException(getString(R.string.rbf_boost_fee_too_low));
        }
        if (!replacement.isOptInFullRBF()) {
            throw new IllegalStateException(getString(R.string.rbf_boost_failed));
        }
        Transaction.verify(wallet.getNetworkParameters().network(), replacement);
    }

    private String outputKey(TransactionOutput output) {
        return java.util.Arrays.toString(output.getScriptBytes()) + ':' + output.getValue().value;
    }

    private void validatePaymentOutputsForNoChange(
            Transaction original, Transaction replacement) {
        java.util.List<TransactionOutput> remaining =
                new java.util.ArrayList<>(replacement.getOutputs());
        for (TransactionOutput originalOutput : original.getOutputs()) {
            int match = -1;
            for (int i = 0; i < remaining.size(); i++) {
                TransactionOutput candidate = remaining.get(i);
                if (java.util.Arrays.equals(originalOutput.getScriptBytes(), candidate.getScriptBytes())
                        && candidate.getValue().compareTo(originalOutput.getValue()) <= 0) {
                    match = i;
                    break;
                }
            }
            if (match < 0) {
                throw new IllegalStateException(getString(R.string.rbf_boost_outputs_changed));
            }
            remaining.remove(match);
        }
    }

    private static final long RBF_SEQUENCE = 0xfffffffdL;

    private static final class RbfReplacement {
        final Transaction transaction;
        final int changeIndex;

        RbfReplacement(Transaction transaction, int changeIndex) {
            this.transaction = transaction;
            this.changeIndex = changeIndex;
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
