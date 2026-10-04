package wallet.main;

import android.content.Context;
import android.net.Uri;

import java.util.List;

import wallet.model.TransactionItem;

/** Contract between the main screen and wallet presenter. */
public interface MainActivityContract {

    interface MainActivityView {
        void setPresenter(MainActivityPresenter presenter);
        void displayDownloadContent(boolean shown);
        void displayProgress(int percent);
        void displayPercentage(int percent);
        void displayMyBalance(String balance);
        void displayBalanceState(String available, String pending);
        void displayMyAddress(String address);
        void displayWalletType(String type);
        void displayTransactions(List<TransactionItem> transactions);
        void showToastMessage(String message);
        Context getActivityContext();
    }

    interface MainActivityPresenter {
        void attachView(MainActivityView view);
        void detachView();
        void subscribe();
        void unsubscribe();
        void refresh();
        void restoreWallet(Uri backupUri);
        void restoreWalletFromMnemonic(String mnemonic, String birthday);
    }
}
