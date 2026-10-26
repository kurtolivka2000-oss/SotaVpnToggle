package com.example.sotavpntoggle;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Невидимая точка входа (тема Theme.NoDisplay — окно не рисуется).
 * Её запускает Aaron Button / «Режимы и сценарии» → «Открыть приложение».
 * Без параметров — переключает VPN; ярлыки передают mode=on/off.
 */
public class TriggerActivity extends Activity {

    public static final String EXTRA_MODE = "mode";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String mode = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = TapAccessibilityService.MODE_TOGGLE;

        if (TapAccessibilityService.isRunning()) {
            TapAccessibilityService.run(Prefs.getLabel(this), mode, null);
        } else {
            Toast.makeText(this,
                    "Sota Toggle: включи службу в «Спец. возможностях»",
                    Toast.LENGTH_LONG).show();
            Intent i = new Intent(this, SettingsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }

        finish();
        overridePendingTransition(0, 0);
    }
}
