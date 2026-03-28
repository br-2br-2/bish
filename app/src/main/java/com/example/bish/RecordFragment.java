package com.example.bish;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordFragment extends Fragment {
    
    private AppDatabase db;
    private ArrayAdapter<String> adapter;
    private List<Expense> expenseList;
    private ListView listView;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_record, container, false);
        
        db = AppDatabase.getDatabase(getContext());
        expenseList = new ArrayList<>();
        adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<>());
        listView = view.findViewById(R.id.listView);
        listView.setAdapter(adapter);
        
        Button btnAdd = view.findViewById(R.id.btnAdd);
        btnAdd.setOnClickListener(v -> showAddDialog());
        
        loadData();
        setupListViewDelete();
        
        return view;
    }
    
    private void loadData() {
        new Thread(() -> {
            expenseList = db.expenseDao().getAllExpenses();
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    adapter.clear();
                    SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                    for (Expense e : expenseList) {
                        String line1 = String.format("%.2f 元 | %s", e.amount, e.category);
                        String line2 = (e.note.isEmpty() ? "" : e.note + " | ") + sdf.format(new Date(e.date));
                        adapter.add(line1 + "\n" + line2);
                    }
                });
            }
        }).start();
    }
    
    private void setupListViewDelete() {
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            Expense expense = expenseList.get(position);
            new AlertDialog.Builder(getContext())
                    .setTitle("删除记录")
                    .setMessage("确定要删除这条记录吗？\n" + String.format("%.2f 元 - %s", expense.amount, expense.category))
                    .setPositiveButton("删除", (dialog, which) -> {
                        new Thread(() -> {
                            db.expenseDao().delete(expense);
                            if (getActivity() != null) {
                                getActivity().runOnUiThread(this::loadData);
                            }
                        }).start();
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });
    }
    
    private void showAddDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle("添加记账");
        
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 0, 50, 0);
        
        EditText etAmount = new EditText(getContext());
        etAmount.setHint("金额（如 25.8）");
        etAmount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        layout.addView(etAmount);
        
        EditText etCategory = new EditText(getContext());
        etCategory.setHint("类别（如 餐饮）");
        layout.addView(etCategory);
        
        EditText etNote = new EditText(getContext());
        etNote.setHint("备注（可选）");
        layout.addView(etNote);
        
        LinearLayout timeLayout = new LinearLayout(getContext());
        timeLayout.setOrientation(LinearLayout.HORIZONTAL);
        timeLayout.setPadding(0, 10, 0, 0);
        
        TextView tvTimeLabel = new TextView(getContext());
        tvTimeLabel.setText("消费时间:");
        tvTimeLabel.setPadding(0, 0, 10, 0);
        timeLayout.addView(tvTimeLabel);
        
        TextView tvSelectedTime = new TextView(getContext());
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        tvSelectedTime.setText(sdf.format(new Date()));
        tvSelectedTime.setPadding(10, 0, 10, 0);
        tvSelectedTime.setOnClickListener(v -> showDateTimePicker(tvSelectedTime));
        timeLayout.addView(tvSelectedTime);
        
        layout.addView(timeLayout);
        builder.setView(layout);
        
        builder.setPositiveButton("保存", (dialog, which) -> {
            String amountStr = etAmount.getText().toString().trim();
            String category = etCategory.getText().toString().trim();
            String note = etNote.getText().toString().trim();
            
            if (amountStr.isEmpty() || category.isEmpty()) {
                Toast.makeText(getContext(), "金额和类别不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            
            try {
                double amount = Double.parseDouble(amountStr);
                if (amount <= 0) {
                    Toast.makeText(getContext(), "金额必须大于 0", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                long selectedTime;
                String timeText = tvSelectedTime.getText().toString();
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
                try {
                    Date parsedDate = sdf.parse(timeText);
                    if (parsedDate != null) {
                        Calendar selectedCal = Calendar.getInstance();
                        selectedCal.setTime(parsedDate);
                        Calendar currentCal = Calendar.getInstance();
                        selectedCal.set(Calendar.YEAR, currentCal.get(Calendar.YEAR));
                        selectedTime = selectedCal.getTimeInMillis();
                    } else {
                        selectedTime = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    selectedTime = System.currentTimeMillis();
                }
                
                Expense e = new Expense();
                e.amount = amount;
                e.category = category;
                e.note = note;
                e.date = selectedTime;
                
                new Thread(() -> {
                    db.expenseDao().insert(e);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(this::loadData);
                    }
                }).start();
                
            } catch (NumberFormatException ex) {
                Toast.makeText(getContext(), "请输入有效数字", Toast.LENGTH_SHORT).show();
            }
        });
        
        builder.setNegativeButton("取消", null);
        builder.show();
    }
    
    private void showDateTimePicker(TextView tvSelectedTime) {
        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        
        AlertDialog.Builder timeBuilder = new AlertDialog.Builder(getContext());
        timeBuilder.setTitle("选择消费时间");
        
        LinearLayout timePickerLayout = new LinearLayout(getContext());
        timePickerLayout.setOrientation(LinearLayout.VERTICAL);
        timePickerLayout.setPadding(50, 0, 50, 0);
        
        DatePicker datePicker = new DatePicker(getContext());
        datePicker.init(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH), null);
        timePickerLayout.addView(datePicker);
        
        TimePicker timePicker = new TimePicker(getContext());
        timePicker.setHour(calendar.get(Calendar.HOUR_OF_DAY));
        timePicker.setMinute(calendar.get(Calendar.MINUTE));
        timePickerLayout.addView(timePicker);
        
        timeBuilder.setView(timePickerLayout);
        
        timeBuilder.setPositiveButton("确定", (dialog, which) -> {
            int year = datePicker.getYear();
            int month = datePicker.getMonth();
            int day = datePicker.getDayOfMonth();
            int hour = timePicker.getHour();
            int minute = timePicker.getMinute();
            
            Calendar selectedCalendar = Calendar.getInstance();
            selectedCalendar.set(year, month, day, hour, minute);
            
            tvSelectedTime.setText(sdf.format(selectedCalendar.getTime()));
        });
        
        timeBuilder.setNegativeButton("取消", null);
        timeBuilder.show();
    }
    
    @Override
    public void onResume() {
        super.onResume();
        loadData();
    }
}
