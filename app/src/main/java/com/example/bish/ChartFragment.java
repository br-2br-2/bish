package com.example.bish;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.ValueFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ChartFragment extends Fragment {
    
    private AppDatabase db;
    private PieChart pieChart;
    private TextView tvTotal;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chart, container, false);
        
        db = AppDatabase.getDatabase(getContext());
        pieChart = view.findViewById(R.id.pieChart);
        tvTotal = view.findViewById(R.id.tvTotal);
        
        setupChart();
        loadChartData();
        
        return view;
    }
    
    private void setupChart() {
        pieChart.setUsePercentValues(true);
        pieChart.getDescription().setEnabled(false);
        pieChart.setExtraOffsets(5, 10, 5, 5);
        pieChart.setDragDecelerationFrictionCoef(0.95f);
        pieChart.setDrawHoleEnabled(true);
        pieChart.setHoleColor(Color.WHITE);
        pieChart.setTransparentCircleRadius(61f);
        pieChart.setRotationEnabled(true);
        pieChart.setHighlightPerTapEnabled(true);
        pieChart.animateY(1400);
        
        // 设置图例
        pieChart.getLegend().setEnabled(true);
        pieChart.getLegend().setOrientation(PieChart.LegendOrientation.VERTICAL);
        pieChart.getLegend().setHorizontalAlignment(PieChart.LegendHorizontalAlignment.RIGHT);
    }
    
    private void loadChartData() {
        new Thread(() -> {
            List<Expense> expenses = db.expenseDao().getAllExpenses();
            
            // 按类别聚合
            Map<String, Float> categoryMap = new HashMap<>();
            double total = 0;
            for (Expense e : expenses) {
                categoryMap.merge(e.category, (float) e.amount, Float::sum);
                total += e.amount;
            }
            
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    tvTotal.setText("总支出：¥" + String.format(Locale.getDefault(), "%.2f", total));
                    
                    if (categoryMap.isEmpty()) {
                        pieChart.setNoDataText("暂无数据，请先添加记账记录");
                        return;
                    }
                    
                    List<PieEntry> entries = new ArrayList<>();
                    for (Map.Entry<String, Float> entry : categoryMap.entrySet()) {
                        entries.add(new PieEntry(entry.getValue(), entry.getKey()));
                    }
                    
                    PieDataSet dataSet = new PieDataSet(entries, "支出分类");
                    dataSet.setColors(getChartColors(categoryMap.size()));
                    dataSet.setValueTextSize(12f);
                    dataSet.setValueTextColor(Color.WHITE);
                    dataSet.setValueFormatter(new ValueFormatter() {
                        @Override
                        public String getFormattedValue(float value) {
                            return String.format(Locale.getDefault(), "¥%.0f", value);
                        }
                    });
                    
                    PieData pieData = new PieData(dataSet);
                    pieChart.setData(pieData);
                    pieChart.invalidate();
                });
            }
        }).start();
    }
    
    private int[] getChartColors(int count) {
        int[] colors = {
            Color.rgb(255, 99, 132),
            Color.rgb(54, 162, 235),
            Color.rgb(255, 206, 86),
            Color.rgb(75, 192, 192),
            Color.rgb(153, 102, 255),
            Color.rgb(255, 159, 64),
            Color.rgb(199, 199, 199),
            Color.rgb(83, 102, 255),
            Color.rgb(255, 99, 255),
            Color.rgb(99, 255, 132)
        };
        
        if (count <= colors.length) {
            int[] result = new int[count];
            System.arraycopy(colors, 0, result, 0, count);
            return result;
        }
        return colors;
    }
    
    @Override
    public void onResume() {
        super.onResume();
        loadChartData();
    }
}
