package com.example.bish;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
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
    
    // 预设类别选项
    private static final String[] PRESET_CATEGORIES = {
        "餐饮", "交通", "购物", "娱乐", "医疗", "教育", "住房", "通讯", "其他"
    };

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
        findViewById(R.id.btnExport).setOnClickListener(v -> showExportOptionsDialog());
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
                String result = "LSTM预测明日支出: ¥" + String.format("%.2f", pred);
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

        // 创建类别选择Spinner
        Spinner spinnerCategory = new Spinner(this);
        
        // 准备类别选项列表，包含预设选项和"自定义"选项
        List<String> categories = new ArrayList<>();
        categories.addAll(Arrays.asList(PRESET_CATEGORIES));
        categories.add("自定义");
        
        ArrayAdapter<String> categoryAdapter = new ArrayAdapter<>(this, 
            android.R.layout.simple_spinner_dropdown_item, categories);
        spinnerCategory.setAdapter(categoryAdapter);
        
        layout.addView(spinnerCategory);

        // 自定义类别输入框，初始时隐藏
        EditText etCustomCategory = new EditText(this);
        etCustomCategory.setHint("输入自定义类别");
        etCustomCategory.setVisibility(View.GONE); // 初始隐藏
        layout.addView(etCustomCategory);

        // 监听Spinner选择变化
        spinnerCategory.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == categories.size() - 1) { // 选择了"自定义"
                    etCustomCategory.setVisibility(View.VISIBLE);
                    etCustomCategory.requestFocus();
                } else { // 选择了预设类别
                    etCustomCategory.setVisibility(View.GONE);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不做任何操作
            }
        });

        EditText etNote = new EditText(this);
        etNote.setHint("备注（可选）");
        layout.addView(etNote);

        // 添加时间选择按钮
        LinearLayout timeLayout = new LinearLayout(this);
        timeLayout.setOrientation(LinearLayout.HORIZONTAL);
        timeLayout.setPadding(0, 10, 0, 0);

        TextView tvTimeLabel = new TextView(this);
        tvTimeLabel.setText("消费时间:");
        tvTimeLabel.setPadding(0, 0, 10, 0);
        timeLayout.addView(tvTimeLabel);

        TextView tvSelectedTime = new TextView(this);
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        tvSelectedTime.setText(sdf.format(new Date()));
        tvSelectedTime.setPadding(10, 0, 10, 0);
        tvSelectedTime.setBackgroundResource(R.drawable.border_background); // 需要创建一个边框drawable
        tvSelectedTime.setOnClickListener(v -> showDateTimePicker(tvSelectedTime));
        timeLayout.addView(tvSelectedTime);

        layout.addView(timeLayout);

        builder.setView(layout);

        builder.setPositiveButton("保存", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String amountStr = etAmount.getText().toString().trim();
                
                String category;
                int selectedPosition = spinnerCategory.getSelectedItemPosition();
                
                if (selectedPosition == categories.size() - 1) { // 选择了"自定义"
                    category = etCustomCategory.getText().toString().trim();
                    if (category.isEmpty()) {
                        Toast.makeText(MainActivity.this, "请输入自定义类别", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    
                    // 检查自定义类别是否在预设类别中，如果是则自动选择对应的预设项
                    for (int i = 0; i < PRESET_CATEGORIES.length; i++) {
                        if (PRESET_CATEGORIES[i].equals(category)) {
                            spinnerCategory.setSelection(i);
                            etCustomCategory.setVisibility(View.GONE);
                            break;
                        }
                    }
                } else { // 选择了预设类别
                    category = (String) spinnerCategory.getItemAtPosition(selectedPosition);
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

                    // 解析选择的时间
                    long selectedTime;
                    String timeText = tvSelectedTime.getText().toString();
                    SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                    try {
                        Date parsedDate = sdf.parse(timeText);
                        if (parsedDate != null) {
                            // 获取当前年份并设置到解析的日期中
                            Calendar selectedCal = Calendar.getInstance();
                            selectedCal.setTime(parsedDate);
                            Calendar currentCal = Calendar.getInstance();
                            selectedCal.set(Calendar.YEAR, currentCal.get(Calendar.YEAR));
                            selectedTime = selectedCal.getTimeInMillis();
                        } else {
                            selectedTime = System.currentTimeMillis();
                        }
                    } catch (Exception e) {
                        selectedTime = System.currentTimeMillis(); // 默认使用当前时间
                    }

                    // 保存到数据库
                    Expense e = new Expense();
                    e.amount = amount;
                    e.category = category;
                    e.note = note;
                    e.date = selectedTime;

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

    private void showDateTimePicker(TextView tvSelectedTime) {
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

        // 创建时间选择器对话框
        AlertDialog.Builder timeBuilder = new AlertDialog.Builder(this);
        timeBuilder.setTitle("选择消费时间");

        // 创建时间选择布局
        LinearLayout timePickerLayout = new LinearLayout(this);
        timePickerLayout.setOrientation(LinearLayout.VERTICAL);
        timePickerLayout.setPadding(50, 0, 50, 0);

        // 日期选择器
        DatePicker datePicker = new DatePicker(this);
        datePicker.init(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH), null);
        timePickerLayout.addView(datePicker);

        // 时间选择器
        TimePicker timePicker = new TimePicker(this);
        timePicker.setHour(calendar.get(Calendar.HOUR_OF_DAY));
        timePicker.setMinute(calendar.get(Calendar.MINUTE));
        timePicker.setOnTimeChangedListener(new TimePicker.OnTimeChangedListener() {
            @Override
            public void onTimeChanged(TimePicker view, int hourOfDay, int minute) {
                // 当时间改变时更新显示
            }
        });
        timePickerLayout.addView(timePicker);

        timeBuilder.setView(timePickerLayout);

        timeBuilder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int year = datePicker.getYear();
                int month = datePicker.getMonth();
                int day = datePicker.getDayOfMonth();
                int hour = timePicker.getHour();
                int minute = timePicker.getMinute();

                Calendar selectedCalendar = Calendar.getInstance();
                selectedCalendar.set(year, month, day, hour, minute);

                tvSelectedTime.setText(sdf.format(selectedCalendar.getTime()));
            }
        });

        timeBuilder.setNegativeButton("取消", null);
        timeBuilder.show();
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

    private void showExportOptionsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("导出CSV");
        
        String[] options = {"选择位置导出", "导出到默认位置"};
        
        builder.setItems(options, (dialog, which) -> {
            if (which == 0) {
                // 选择位置导出
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("text/csv");
                intent.putExtra(Intent.EXTRA_TITLE, "expense_data.csv");
                startActivityForResult(intent, 1001);
            } else {
                // 导出到默认位置
                exportToDefaultLocation();
            }
        });
        
        builder.show();
    }

    private void exportToDefaultLocation() {
        executor.execute(() -> {
            try {
                List<Expense> expenses = db.expenseDao().getAllExpenses();
                
                // 获取外部存储目录
                File downloadsDir = new File(getExternalFilesDir(null), "CSV_exports");
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs();
                }
                
                File csvFile = new File(downloadsDir, "expense_data_" + System.currentTimeMillis() + ".csv");
                
                writeCsvToFile(expenses, csvFile);
                
                runOnUiThread(() -> {
                    Toast.makeText(this, "CSV文件已导出到: " + csvFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    private void writeCsvToFile(List<Expense> expenses, File file) throws IOException {
        try (FileWriter writer = new FileWriter(file);
             BufferedWriter bufferedWriter = new BufferedWriter(writer)) {
            
            // 写入CSV头部
            bufferedWriter.write("ID,金额,类别,日期时间,备注\n");
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            
            // 写入数据行
            for (Expense expense : expenses) {
                String dateStr = sdf.format(new Date(expense.date));
                bufferedWriter.write(String.format(Locale.getDefault(), "%d,%.2f,%s,%s,%s\n",
                        expense.id, expense.amount, escapeCsvField(expense.category),
                        dateStr, escapeCsvField(expense.note)));
            }
        }
    }
    
    private String escapeCsvField(String field) {
        if (field == null) return "";
        // 如果字段包含逗号、引号或换行符，则用双引号包围并转义内部的双引号
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                exportToUri(uri);
            }
        }
    }
    
    private void exportToUri(Uri uri) {
        executor.execute(() -> {
            try {
                List<Expense> expenses = db.expenseDao().getAllExpenses();
                
                try (OutputStream outputStream = getContentResolver().openOutputStream(uri);
                     OutputStreamWriter writer = new OutputStreamWriter(outputStream);
                     BufferedWriter bufferedWriter = new BufferedWriter(writer)) {
                    
                    // 写入CSV头部
                    bufferedWriter.write("ID,金额,类别,日期时间,备注\n");
                    
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    
                    // 写入数据行
                    for (Expense expense : expenses) {
                        String dateStr = sdf.format(new Date(expense.date));
                        bufferedWriter.write(String.format(Locale.getDefault(), "%d,%.2f,%s,%s,%s\n",
                                expense.id, expense.amount, escapeCsvField(expense.category),
                                dateStr, escapeCsvField(expense.note)));
                    }
                }
                
                runOnUiThread(() -> {
                    Toast.makeText(this, "CSV文件已成功导出", Toast.LENGTH_SHORT).show();
                });
                
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
}