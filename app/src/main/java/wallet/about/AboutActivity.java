/** Displays wallet information. */

package wallet.about;

import wallet.main.BaseActivity;

import android.os.Bundle;
import androidx.appcompat.widget.Toolbar;

import wallet.main.R;

public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar_about);
        setSupportActionBar(toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.about_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        toolbar.setNavigationOnClickListener(v -> finish());
    }
}
