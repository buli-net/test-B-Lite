package wallet.contacts;

import wallet.main.BaseActivity;

import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.bitcoinj.base.Address;
import org.bitcoinj.kits.WalletAppKit;

import java.util.List;

import wallet.main.MainActivityPresenter;
import wallet.main.R;
import wallet.ui.TextViewUtils;

/** Local Bitcoin address book. No private keys are stored here. */
public final class AddressBookActivity extends BaseActivity {
    public static final String EXTRA_SELECTED_ADDRESS = "selected_address";

    private LinearLayout list;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_address_book);
        Toolbar toolbar = findViewById(R.id.toolbar_address_book);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.address_book_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());
        list = findViewById(R.id.addressBookList);
        findViewById(R.id.addAddressButton).setOnClickListener(v -> showAddDialog());
        render();
    }

    private void render() {
        list.removeAllViews();
        List<AddressBookStore.Entry> entries = AddressBookStore.load(this);
        if (entries.isEmpty()) {
            TextView empty = (TextView) getLayoutInflater().inflate(
                    R.layout.item_empty_message, list, false);
            empty.setText(R.string.address_book_empty);
            list.addView(empty);
            return;
        }

        for (AddressBookStore.Entry entry : entries) {
            View row = getLayoutInflater().inflate(R.layout.item_address_book, list, false);
            TextView name = row.findViewById(R.id.addressBookName);
            TextView address = row.findViewById(R.id.addressBookAddress);
            TextViewUtils.configureSelectableMiddleEllipsis(address);
            Button select = row.findViewById(R.id.addressBookUse);
            Button delete = row.findViewById(R.id.addressBookDelete);

            name.setText(entry.name);
            TextViewUtils.setTextIfChanged(address, entry.address);
            select.setOnClickListener(v -> selectAddress(entry.address));
            delete.setOnClickListener(v -> confirmDelete(entry));
            list.addView(row);
        }
    }

    private void showAddDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_address_book_add, null);
        EditText name = content.findViewById(R.id.addressBookNameInput);
        EditText address = content.findViewById(R.id.addressBookAddressInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.address_book_add_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_save, null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = name.getText().toString().trim();
            String value = address.getText().toString().trim();
            if (TextUtils.isEmpty(label) || TextUtils.isEmpty(value)) {
                Toast.makeText(this, R.string.address_book_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isValidAddress(value)) {
                Toast.makeText(this, R.string.address_book_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!AddressBookStore.add(this, label, value)) {
                Toast.makeText(this, R.string.address_book_duplicate, Toast.LENGTH_SHORT).show();
                return;
            }
            dialog.dismiss();
            render();
        }));
        dialog.show();
    }

    private boolean isValidAddress(String value) {
        org.bitcoinj.core.NetworkParameters parameters = MainActivityPresenter.getActiveParameters();
        if (parameters == null) return false;
        try {
            Address.fromString(parameters, value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void confirmDelete(AddressBookStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.address_book_delete_title)
                .setMessage(entry.name + "\n" + entry.address)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.address_book_delete, (d, w) -> {
                    AddressBookStore.remove(this, entry.address);
                    render();
                }).show();
    }

    private void selectAddress(String address) {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_SELECTED_ADDRESS, address));
        finish();
    }

}
