package wallet.transaction;

import android.content.Context;

import org.bitcoinj.base.Coin;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionConfidence;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.wallet.Wallet;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

import wallet.main.R;
import wallet.model.TransactionItem;

/** Converts bitcoinj transactions into UI data. */
public final class TransactionMapper {

    private TransactionMapper() {
    }

    /**
     * Maps only transactions that actually involve the selected watched script.
     * Both incoming outputs and inputs spending previous outputs belonging to
     * this exact watched script are included.
     */
    public static List<TransactionItem> mapForWatchedScript(
            Context context, Wallet wallet, org.bitcoinj.script.Script watchedScript) {
        List<TransactionItem> items = new ArrayList<>();
        if (wallet == null || watchedScript == null) {
            return items;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US);

        Map<Sha256Hash, Transaction> walletTransactions = indexWalletTransactions(wallet);

        for (Transaction transaction : wallet.getTransactionsByTime()) {
            Coin received = Coin.ZERO;
            for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
                if (watchedScript.equals(output.getScriptPubKey())) {
                    received = received.add(output.getValue());
                }
            }

            Coin sent = valueSentFromWatchedScript(walletTransactions, transaction, watchedScript);
            Coin net = received.subtract(sent);

            if (net.isZero()) {
                continue;
            }

            addItem(items, context, wallet, walletTransactions, transaction, net, dateFormat);
        }

        return items;
    }

    /** Maps only transactions involving outputs controlled by the main wallet keys. */
    public static List<TransactionItem> mapForMainWallet(Context context, Wallet wallet) {
        List<TransactionItem> items = new ArrayList<>();
        if (wallet == null) {
            return items;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US);

        Map<Sha256Hash, Transaction> walletTransactions = indexWalletTransactions(wallet);

        for (Transaction transaction : wallet.getTransactionsByTime()) {
            Coin received = Coin.ZERO;
            for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
                if (output.isMine(wallet)) {
                    received = received.add(output.getValue());
                }
            }

            Coin sent = valueSentFromMainWallet(wallet, walletTransactions, transaction);
            Coin net = received.subtract(sent);

            if (net.isZero()) {
                continue;
            }

            addItem(items, context, wallet, walletTransactions, transaction, net, dateFormat);
        }
        return items;
    }

    /** Sum inputs that spend outputs belonging to the exact watched script. */
    private static Coin valueSentFromWatchedScript(
            Map<Sha256Hash, Transaction> walletTransactions,
            Transaction transaction, org.bitcoinj.script.Script watchedScript) {
        Coin sent = Coin.ZERO;
        for (org.bitcoinj.core.TransactionInput input : transaction.getInputs()) {
            org.bitcoinj.core.TransactionOutput connected = findConnectedOutput(walletTransactions, input);
            if (connected != null && watchedScript.equals(connected.getScriptPubKey())) {
                sent = sent.add(connected.getValue());
            }
        }
        return sent;
    }

    /** Sum inputs that spend outputs owned by the main wallet keys, excluding watched scripts. */
    private static Coin valueSentFromMainWallet(
            Wallet wallet, Map<Sha256Hash, Transaction> walletTransactions, Transaction transaction) {
        Coin sent = Coin.ZERO;
        for (org.bitcoinj.core.TransactionInput input : transaction.getInputs()) {
            org.bitcoinj.core.TransactionOutput connected = findConnectedOutput(walletTransactions, input);
            if (connected != null && connected.isMine(wallet)) {
                sent = sent.add(connected.getValue());
            }
        }
        return sent;
    }

    /**
     * Resolve an input against transactions stored by this wallet.
     *
     * bitcoinj 0.17.1 exposes TransactionInput#getConnectedOutput() without
     * arguments. For historical wallet transactions the safest way to resolve
     * an input is by its outpoint against the wallet's complete transaction set.
     */
    private static org.bitcoinj.core.TransactionOutput findConnectedOutput(
            Map<Sha256Hash, Transaction> walletTransactions,
            org.bitcoinj.core.TransactionInput input) {
        if (walletTransactions == null || input == null) {
            return null;
        }

        org.bitcoinj.core.TransactionOutPoint outpoint = input.getOutpoint();
        if (outpoint == null || outpoint.hash() == null) {
            return null;
        }

        Transaction parent = walletTransactions.get(outpoint.hash());
        if (parent == null) {
            return null;
        }

        long index = outpoint.index();
        if (index < 0 || index >= parent.getOutputs().size()) {
            return null;
        }

        return parent.getOutput((int) index);
    }

    private static Map<Sha256Hash, Transaction> indexWalletTransactions(Wallet wallet) {
        Map<Sha256Hash, Transaction> result = new HashMap<>();
        for (Transaction transaction : wallet.getTransactions(true)) {
            result.put(transaction.getTxId(), transaction);
        }
        return result;
    }

    private static void addItem(
            List<TransactionItem> items,
            Context context,
            Wallet wallet,
            Map<Sha256Hash, Transaction> walletTransactions,
            Transaction transaction,
            Coin net,
            SimpleDateFormat dateFormat) {
        String type = net.isPositive()
                ? context.getString(R.string.transaction_received)
                : context.getString(R.string.transaction_sent);
        String amount = net.isPositive()
                ? "+" + net.toFriendlyString()
                : net.toFriendlyString();

        String txId = transaction.getTxId().toString();
        String time = transaction.updateTime().isPresent()
                ? dateFormat.format(Date.from(transaction.updateTime().get()))
                : context.getString(R.string.unknown_time);

        TransactionConfidence confidence = transaction.getConfidence();
        int depth = confidence == null ? 0 : confidence.getDepthInBlocks();
        int peers = confidence == null ? 0 : confidence.numBroadcastPeers();

        String confirmations = depth > 0
                ? context.getString(depth == 1
                ? R.string.confirmed_one
                : R.string.confirmed_many, depth)
                : context.getString(R.string.unconfirmed);

        String state;
        if (depth > 0) {
            state = context.getString(R.string.transaction_state_confirmed);
        } else if (confidence != null
                && confidence.getConfidenceType() == TransactionConfidence.ConfidenceType.DEAD) {
            state = context.getString(R.string.transaction_state_dead);
        } else if (peers > 0) {
            state = context.getString(R.string.transaction_state_broadcast, peers);
        } else {
            state = context.getString(R.string.transaction_state_pending);
        }

        String counterparty = counterpartyAddress(wallet, walletTransactions, transaction, net);
        items.add(new TransactionItem(
                type,
                amount,
                time,
                confirmations,
                txId,
                counterparty,
                context.getString(R.string.transaction_peers, peers),
                state));
    }

    private static String counterpartyAddress(Wallet wallet, Map<Sha256Hash, Transaction> walletTransactions, Transaction transaction, Coin net) {
        if (wallet == null || transaction == null) return "";
        try {
            if (net.isPositive()) {
                for (org.bitcoinj.core.TransactionInput input : transaction.getInputs()) {
                    org.bitcoinj.core.TransactionOutput output = findConnectedOutput(
                            walletTransactions, input);
                    if (output != null) {
                        return output.getScriptPubKey().getToAddress(wallet.getParams()).toString();
                    }
                }
            } else {
                for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
                    if (!output.isMine(wallet)) {
                        return output.getScriptPubKey().getToAddress(wallet.getParams()).toString();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

}
