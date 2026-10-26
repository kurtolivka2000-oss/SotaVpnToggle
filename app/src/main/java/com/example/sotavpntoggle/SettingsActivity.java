package com.example.sotavpntoggle;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Экран настроек и проверки. Интерфейс собран в коде, без XML-разметки. */
public class SettingsActivity extends Activity {

    private TextView status;
    private EditText labelInput;
    private TextView logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(status);

        root.addView(text("1. Включи службу «Sota Toggle» в «Спец. возможностях» → «Установленные приложения». "
                + "Если переключатель серый — сначала нажми вторую кнопку, там ⋮ → «Разрешить ограниченные настройки»."));

        root.addView(button("Открыть «Спец. возможности»", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(button("Открыть сведения о приложении", v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));

        root.addView(text("2. Текст на плитке Sota в шторке (достаточно части, регистр не важен):"));

        labelInput = new EditText(this);
        labelInput.setSingleLine(true);
        labelInput.setInputType(InputType.TYPE_CLASS_TEXT);
        labelInput.setText(Prefs.getLabel(this));
        root.addView(labelInput);

        root.addView(button("Сохранить", v -> {
            Prefs.setLabel(this, labelInput.getText().toString());
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
        }));

        root.addView(text("3. Проверка: шторка откроется, плитка нажмётся, шторка закроется. "
                + "Результат появится ниже."));

        root.addView(button("Тест: переключить VPN", v -> {
            Prefs.setLabel(this, labelInput.getText().toString());
            logView.setText("Выполняю…");
            boolean started = TapAccessibilityService.run(Prefs.getLabel(this),
                    TapAccessibilityService.MODE_TOGGLE,
                    (ok, log) -> logView.setText((ok ? "✅ Готово\n\n" : "❌ Не получилось\n\n") + log));
            if (!started) logView.setText("❌ Служба выключена — см. шаг 1.");
        }));

        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(12), 0, 0);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = TapAccessibilityService.isRunning();
        status.setText(on ? "Служба: включена ✅" : "Служба: ВЫКЛЮЧЕНА ❌");
    }

    private TextView text(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
