package wallet.send;

import org.bitcoinj.base.Coin;

/** Immutable details for a pending transaction review. */
public final class SendTransactionPreview {

    private final Coin balance;
    private final Coin amount;
    private final Coin fee;
    private final Coin totalDebit;
    private final Coin remainingBalance;
    private final int feeRateSatVb;
    private final boolean replaceByFee;
    private final String recipient;
    private final int sizeBytes;

    public SendTransactionPreview(
            Coin balance,
            Coin amount,
            Coin fee,
            Coin totalDebit,
            Coin remainingBalance,
            int feeRateSatVb,
            boolean replaceByFee,
            String recipient,
            int sizeBytes) {
        this.balance = balance;
        this.amount = amount;
        this.fee = fee;
        this.totalDebit = totalDebit;
        this.remainingBalance = remainingBalance;
        this.feeRateSatVb = feeRateSatVb;
        this.replaceByFee = replaceByFee;
        this.recipient = recipient;
        this.sizeBytes = sizeBytes;
    }

    public Coin balance() {
        return balance;
    }

    public Coin amount() {
        return amount;
    }

    public Coin fee() {
        return fee;
    }

    public Coin totalDebit() {
        return totalDebit;
    }

    public Coin remainingBalance() {
        return remainingBalance;
    }

    public int feeRateSatVb() {
        return feeRateSatVb;
    }

    public boolean replaceByFee() {
        return replaceByFee;
    }

    public String recipient() {
        return recipient;
    }

    public int sizeBytes() {
        return sizeBytes;
    }
}
