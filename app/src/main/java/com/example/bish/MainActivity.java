package com.example.bish;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.AssetFileDescriptor;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import org.tensorflow.lite.Interpreter;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private ArrayAdapter<String> adapter;
    private List<Expense> expenseList;

    private AppDatabase db;
    private TextView tvPrediction;

    /* 1. 单线程池，生命周期跟随 Activity */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = AppDatabase.getDatabase(this);
        tvPrediction = findViewById(R.id.tvPrediction);
        expenseList = new ArrayList<>();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<>());
        ListView listView = findViewById(R.id.listView);
        listView.setAdapter(adapter);

        // 加载已有数据
        loadData();
        setupListViewDelete();
        // 添加按钮点击事件
        findViewById(R.id.btnAdd).setOnClickListener(v -> showAddDialog());
        findViewById(R.id.btnPredict).setOnClickListener(v -> predictExpense());
    }

    /* 2. 预测入口：把任务扔进线程池 */
    private void predictExpense() {
        executor.execute(() -> {
            List<Expense> expenses = db.expenseDao().getAllExpenses();
            if (expenses.size() < 15) {
                double total = 0;
                for (Expense e : expenses) total += e.amount;
                double avg = expenses.isEmpty() ? 0 : total / expenses.size();
                runOnUiThread(() ->
                        tvPrediction.setText("数据不足，预测支出: ¥" + String.format("%.2f", avg))
                );
                return;
            }

            try {
                /* 2.1 按日聚合 */
                Map<String, Double> dailyMap = new HashMap<>();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                for (Expense e : expenses) {
                    String key = sdf.format(new Date(e.date));
                    dailyMap.merge(key, e.amount, Double::sum);
                }
                List<String> sorted = new ArrayList<>(dailyMap.keySet());
                Collections.sort(sorted);
                if (sorted.size() < 30) {
                    runOnUiThread(() -> tvPrediction.setText("需至少30天数据"));
                    return;
                }

                /* 2.2 取最近30天 & 归一化 */
                float[] input = new float[30];
                float min = readFloatFromAsset("scaler_min.txt");
                float scale = readFloatFromAsset("scaler_scale.txt");
                for (int i = 0; i < 30; i++) {
                    String day = sorted.get(sorted.size() - 30 + i);
                    input[i] = (float) ((dailyMap.get(day) - min) / scale);
                }

                /* 2.3 TFLite 推理 */
                ByteBuffer buf = ByteBuffer.allocateDirect(30 * 4).order(ByteOrder.nativeOrder());
                for (float f : input) buf.putFloat(f);
                buf.rewind();

                Interpreter tflite = new Interpreter(loadModelFile());
                float[][] out = new float[1][1];
                tflite.run(buf, out);
                tflite.close();

                /* 2.4 反归一化并展示 */
                float pred = out[0][0] * scale + min;
                String result = "LSTM预测下一天支出: ¥" + String.format("%.2f", pred);
                runOnUiThread(() -> tvPrediction.setText(result));

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> tvPrediction.setText("预测失败: " + e.getMessage()));
            }
        });
    }

    /* 3. 工具方法 */
    private float readFloatFromAsset(String file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open(file)))) {
            return Float.parseFloat(br.readLine().trim());
        } catch (Exception e) {
            e.printStackTrace();
            return 0f;
        }
    }

    private ByteBuffer loadModelFile() throws IOException {
        try (AssetFileDescriptor fd = getAssets().openFd("lstm_expense_model.tflite");
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            return in.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(),
                    fd.getDeclaredLength());
        }
    }

    /* 4. 释放线程池（可选） */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }


    private void setupListViewDelete() {
        ListView listView = findViewById(R.id.listView);
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                Expense expense = expenseList.get(position);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("删除记录")
                        .setMessage("确定要删除这条记录吗？\n" + String.format("%.2f 元 - %s", expense.amount, expense.category))
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                new Thread(() -> {
                                    db.expenseDao().delete(expense); // 需要在 ExpenseDao 中添加 delete 方法
                                    runOnUiThread(() -> {
                                        loadData(); // 重新加载数据
                                        Toast.makeText(MainActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                                    });
                                }).start();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true; // 表示已消费长按事件
            }
        });
    }



    private void showAddDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("添加记账");

        // 创建输入布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 0, 50, 0);

        EditText etAmount = new EditText(this);
        etAmount.setHint("金额（如 25.8）");
        etAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(etAmount);

        // 创建类别选择相关的控件
        LinearLayout categoryLayout = new LinearLayout(this);
        categoryLayout.setOrientation(LinearLayout.HORIZONTAL);
        categoryLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 类别下拉选择框
        Spinner spinnerCategory = new Spinner(this);
        spinnerCategory.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        // 自定义类别输入框
        EditText etCustomCategory = new EditText(this);
        etCustomCategory.setHint("自定义类别");
        etCustomCategory.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        // 添加预设类别选项 - 先添加默认选项，稍后异步更新
        List<String> categories = new ArrayList<>();
        categories.add("请选择类别");
        
        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, categories);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerCategory.setAdapter(spinnerAdapter);

        categoryLayout.addView(spinnerCategory);
        categoryLayout.addView(etCustomCategory);

        layout.addView(categoryLayout);

        EditText etNote = new EditText(this);
        etNote.setHint("备注（可选）");
        layout.addView(etNote);

        builder.setView(layout);

        // 异步加载现有类别
        new Thread(() -> {
            List<String> existingCategories = db.expenseDao().getAllCategories();
            
            runOnUiThread(() -> {
                // 更新Spinner的选项
                List<String> updatedCategories = new ArrayList<>();
                updatedCategories.add("请选择类别");
                updatedCategories.addAll(existingCategories);
                
                spinnerAdapter.clear();
                for(String cat : updatedCategories) {
                    spinnerAdapter.add(cat);
                }
            });
        }).start();

        builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String amountStr = etAmount.getText().toString().trim();
                
                // 获取类别：优先使用自定义类别，如果为空则使用下拉选择的类别
                String customCategory = etCustomCategory.getText().toString().trim();
                String selectedCategory = spinnerCategory.getSelectedItem() != null ? 
                    spinnerCategory.getSelectedItem().toString() : "";
                
                String category;
                if (!customCategory.isEmpty()) {
                    category = customCategory;  // 使用自定义类别
                } else if (!selectedCategory.equals("请选择类别") && !selectedCategory.isEmpty()) {
                    category = selectedCategory;  // 使用选择的类别
                } else {
                    category = "";  // 如果两者都为空，则为空
                }

                String note = etNote.getText().toString().trim();

                if (amountStr.isEmpty() || category.isEmpty()) {
                    Toast.makeText(MainActivity.this, "金额和类别不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }

                try {
                    double amount = Double.parseDouble(amountStr);
                    if (amount <= 0) {
                        Toast.makeText(MainActivity.this, "金额必须大于 0", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 保存到数据库
                    Expense e = new Expense();
                    e.amount = amount;
                    e.category = category;
                    e.note = note;
                    e.date = System.currentTimeMillis();

                    new Thread(() -> {
                        db.expenseDao().insert(e);
                        runOnUiThread(() -> loadData());
                    }).start();

                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "请输入有效数字", Toast.LENGTH_SHORT).show();
                }
            }
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void loadData() {
        new Thread(() -> {
            expenseList = db.expenseDao().getAllExpenses();
            runOnUiThread(() -> {
                adapter.clear();
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                for (Expense e : expenseList) {
                    String line1 = String.format("%.2f 元 | %s", e.amount, e.category);
                    String line2 = (e.note.isEmpty() ? "" : e.note + " | ") + sdf.format(new Date(e.date));
                    adapter.add(line1 + "\n" + line2);
                }
            });
        }).start();
    }
}