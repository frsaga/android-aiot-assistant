package com.example.courseprojectv3;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import com.example.courseprojectv3.data.AppSettings;
import com.example.courseprojectv3.storage.AppStorage;
import com.example.courseprojectv3.util.SystemBars;
import com.example.courseprojectv3.util.ThemeMode;

/**
 * 设置与配置页面。
 *
 * 答辩说明：
 * 这里实现模型选择、API KEY 配置、深浅色切换、Claw 模式开关。
 * 保存后写入 AppStorage，MainActivity 每次发送前都会重新读取，保证配置实时生效。
 */
public class SettingsActivity extends Activity {
    private static final String[] MODELS = {
            "qwen-plus",
            "qwen-turbo",
            "qwen-max",
            "qwen-vl-plus",
            "qwen-vl-max"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppStorage storage = new AppStorage(this);
        AppSettings settings = storage.loadSettings();
        ThemeMode.apply(this, settings.darkMode);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        SystemBars.apply(getWindow(), findViewById(R.id.settingsRoot));

        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        Spinner spinner = findViewById(R.id.modelSpinner);
        EditText apiKeyInput = findViewById(R.id.apiKeyInput);
        Switch darkSwitch = findViewById(R.id.darkSwitch);
        Switch clawSwitch = findViewById(R.id.clawSwitch);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, R.layout.item_spinner_model, MODELS);
        adapter.setDropDownViewResource(R.layout.item_spinner_model_dropdown);
        spinner.setAdapter(adapter);
        // 根据本地保存的 modelName 回显 Spinner 当前选项。
        int modelIndex = 0;
        for (int i = 0; i < MODELS.length; i++) {
            if (MODELS[i].equals(settings.modelName)) {
                modelIndex = i;
                break;
            }
        }
        spinner.setSelection(modelIndex);
        // API Key 只在本机 SharedPreferences 中回显，不写入代码或资源文件。
        apiKeyInput.setText(settings.apiKey);
        darkSwitch.setChecked(settings.darkMode);
        clawSwitch.setChecked(settings.clawMode);

        findViewById(R.id.saveButton).setOnClickListener(v -> {
            // 将 UI 控件状态收集成 AppSettings，再统一交给 AppStorage 持久化。
            AppSettings next = new AppSettings();
            next.modelName = String.valueOf(spinner.getSelectedItem());
            next.apiKey = apiKeyInput.getText().toString().trim();
            next.darkMode = darkSwitch.isChecked();
            next.clawMode = clawSwitch.isChecked();
            // 四项配置作为一个 AppSettings 对象统一保存，避免部分字段漏写。
            storage.saveSettings(next);
            // 夜间模式改变后重建当前页面，让 values-night 资源立即生效。
            ThemeMode.apply(this, next.darkMode);
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
            recreate();
        });
    }
}
