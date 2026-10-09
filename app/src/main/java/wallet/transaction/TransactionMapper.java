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
import wallet.main.WalletSelection;

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

    /**
     * Maps transactions for the main wallet using bitcoinj's signed transaction
     * value as the primary classification source. bitcoinj defines
     * Transaction#getValue(TransactionBag) as received-minus-sent, so a negative
     * value is an outgoing transaction even when it has a change output back to
     * the wallet.
     *
     * Imported-wallet scripts live in the same bitcoinj Wallet instance, so their
     * signed value is removed afterwards to keep the main-wallet scope separate.
     */
    public static List<TransactionItem> mapForMainWallet(Context context, Wallet wallet) {
        List<TransactionItem> items = new ArrayList<>();
        if (wallet == null) {
            return items;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", Locale.US);

        Map<Sha256Hash, Transaction> walletTransactions = indexWalletTransactions(wallet);

        for (Transaction transaction : wallet.getTransactionsByTime()) {
            // The Wallet object also contains imported and watched scopes.
            // Use the bitcoinj aggregate value first, then remove every
            // explicitly separated scope exactly once. This keeps the main
            // wallet classification consistent with the same signed-value
            // semantics while preserving Bitcoin Wallet wallet separation.
            Coin net = valueForMainWallet(context, wallet, transaction, walletTransactions);

            if (net.isZero()) {
                continue;
            }

            addItem(items, context, wallet, walletTransactions, transaction, net, dateFormat);
        }
        return items;
    }

    /**
     * Signed value for the main wallet scope. bitcoinj's transaction value is
     * the authoritative aggregate for the Wallet, so start with it and then
     * remove the exact scripts that Bitcoin Wallet intentionally keeps outside the
     * main-wallet scope (watched and imported).
     */
    public static Coin valueForMainWallet(
            Context context, Wallet wallet, Transaction transaction) {
        return valueForMainWallet(context, wallet, transaction, indexWalletTransactions(wallet));
    }

    private static Coin valueForMainWallet(
            Context context, Wallet wallet, Transaction transaction,
            Map<Sha256Hash, Transaction> walletTransactions) {
        Coin net = safeTransactionValue(transaction, wallet);
        if (context == null || wallet == null || transaction == null) {
            return net;
        }

        java.util.Set<org.bitcoinj.script.Script> excludedScripts =
                new java.util.HashSet<>();
        excludedScripts.addAll(wallet.getWatchedScripts());
        excludedScripts.addAll(WalletSelection.getImportedScripts(context, wallet));

        for (org.bitcoinj.script.Script excludedScript : excludedScripts) {
            net = net.subtract(valueForScript(
                    walletTransactions, transaction, excludedScript));
        }
        return net;
    }

    /** Gross value sent from the main-wallet scope, excluding separated scopes. */
    public static Coin valueSentFromMainWallet(
            Context context, Wallet wallet, Transaction transaction) {
        Coin value = safeValueSentFromMe(transaction, wallet);
        if (context == null || wallet == null || transaction == null) {
            return value;
        }

        Map<Sha256Hash, Transaction> walletTransactions = indexWalletTransactions(wallet);
        java.util.Set<org.bitcoinj.script.Script> excludedScripts = new java.util.HashSet<>();
        excludedScripts.addAll(wallet.getWatchedScripts());
        excludedScripts.addAll(WalletSelection.getImportedScripts(context, wallet));

        for (org.bitcoinj.script.Script excludedScript : excludedScripts) {
            value = value.subtract(valueSentFromScript(
                    walletTransactions, transaction, excludedScript));
        }
        return value.isNegative() ? Coin.ZERO : value;
    }

    /** Gross value received by the main-wallet scope, excluding separated scopes. */
    public static Coin valueSentToMainWallet(
            Context context, Wallet wallet, Transaction transaction) {
        Coin value = safeValueSentToMe(transaction, wallet);
        if (context == null || wallet == null || transaction == null) {
            return value;
        }

        Map<Sha256Hash, Transaction> walletTransactions = indexWalletTransactions(wallet);
        java.util.Set<org.bitcoinj.script.Script> excludedScripts = new java.util.HashSet<>();
        excludedScripts.addAll(wallet.getWatchedScripts());
        excludedScripts.addAll(WalletSelection.getImportedScripts(context, wallet));

        for (org.bitcoinj.script.Script excludedScript : excludedScripts) {
            value = value.subtract(valueReceivedForScript(
                    transaction, excludedScript));
        }
        return value.isNegative() ? Coin.ZERO : value;
    }

    /** Gross value received by an exact script scope. */
    public static Coin valueReceivedForScript(
            Transaction transaction, org.bitcoinj.script.Script script) {
        if (transaction == null || script == null) {
            return Coin.ZERO;
        }
        Coin received = Coin.ZERO;
        for (org.bitcoinj.core.TransactionOutput output : transaction.getOutputs()) {
            if (script.equals(output.getScriptPubKey())) {
                received = received.add(output.getValue());
            }
        }
        return received;
    }

    /** Gross value spent from an exact script scope. */
    public static Coin valueSentFromScript(
            Map<Sha256Hash, Transaction> walletTransactions,
            Transaction transaction, org.bitcoinj.script.Script script) {
        return valueSentFromWatchedScript(walletTransactions, transaction, script);
    }

    /** Signed value for an exact script scope. */
    public static Coin valueForScriptScope(
            Map<Sha256Hash, Transaction> walletTransactions,
            Transaction transaction, org.bitcoinj.script.Script script) {
        return valueReceivedForScript(transaction, script)
                .subtract(valueSentFromScript(walletTransactions, transaction, script));
    }

    private static Coin safeValueSentFromMe(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValueSentFromMe(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
    }

    private static Coin safeValueSentToMe(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValueSentToMe(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
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

    /**
     * bitcoinj 0.17.1's canonical signed wallet value:
     * getValue(wallet) = valueSentToMe(wallet) - valueSentFromMe(wallet).
     */
    private static Coin safeTransactionValue(Transaction transaction, Wallet wallet) {
        try {
            Coin value = transaction.getValue(wallet);
            return value == null ? Coin.ZERO : value;
        } catch (Exception ignored) {
            return Coin.ZERO;
        }
    }

    /** Calculates the signed value belonging to one exact script scope. */
    private static Coin valueForScript(
            Map<Sha256Hash, Transaction> walletTransactions,
            Transaction transaction,
            org.bitcoinj.script.Script script) {
        return valueForScriptScope(walletTransactions, transaction, script);
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

        try {
            org.bitcoinj.core.TransactionOutput connected = input.getConnectedOutput();
            if (connected != null) {
                return connected;
            }
        } catch (Exception ignored) {
            // Fall through to the wallet transaction index.
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
