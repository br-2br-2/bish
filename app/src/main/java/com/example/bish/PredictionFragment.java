package com.example.bish;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.tensorflow.lite.Interpreter;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PredictionFragment extends Fragment {
    
    private AppDatabase db;
    private TextView tvPrediction;
    private TextView tvAnalysis;
    private ExecutorService executor;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_prediction, container, false);
        
        db = AppDatabase.getDatabase(getContext());
        tvPrediction = view.findViewById(R.id.tvPrediction);
        tvAnalysis = view.findViewById(R.id.tvAnalysis);
        executor = Executors.newSingleThreadExecutor();
        
        Button btnPredict = view.findViewById(R.id.btnPredict);
        btnPredict.setOnClickListener(v -> predictExpense());
        
        return view;
    }
    
    private void predictExpense() {
        executor.execute(() -> {
            List<Expense> expenses = db.expenseDao().getAllExpenses();
            
            if (expenses.size() < 15) {
                double total = 0;
                for (Expense e : expenses) total += e.amount;
                double avg = expenses.isEmpty() ? 0 : total / expenses.size();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        tvPrediction.setText("数据不足，预测支出：¥" + String.format(Locale.getDefault(), "%.2f", avg));
                        tvAnalysis.setText("提示：需要至少 15 条记录才能进行简单预测");
                    });
                }
                return;
            }
            
            try {
                // 按日聚合
                Map<String, Double> dailyMap = new HashMap<>();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                for (Expense e : expenses) {
                    String key = sdf.format(new Date(e.date));
                    dailyMap.merge(key, e.amount, Double::sum);
                }
                
                List<String> sorted = new ArrayList<>(dailyMap.keySet());
                Collections.sort(sorted);
                
                if (sorted.size() < 30) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            tvPrediction.setText("需至少 30 天数据才能使用 LSTM 预测");
                            double total = 0;
                            for (Expense e : expenses) total += e.amount;
                            double avg = total / expenses.size();
                            tvAnalysis.setText("当前平均每日支出：¥" + String.format(Locale.getDefault(), "%.2f", avg));
                        });
                    }
                    return;
                }
                
                // 取最近 30 天 & 归一化
                float[] input = new float[30];
                float min = readFloatFromAsset("scaler_min.txt");
                float scale = readFloatFromAsset("scaler_scale.txt");
                
                for (int i = 0; i < 30; i++) {
                    String day = sorted.get(sorted.size() - 30 + i);
                    input[i] = (float) ((dailyMap.get(day) - min) / scale);
                }
                
                // TFLite 推理
                ByteBuffer buf = ByteBuffer.allocateDirect(30 * 4).order(ByteOrder.nativeOrder());
                for (float f : input) buf.putFloat(f);
                buf.rewind();
                
                Interpreter tflite = new Interpreter(loadModelFile());
                float[][] out = new float[1][1];
                tflite.run(buf, out);
                tflite.close();
                
                // 反归一化并展示
                float pred = out[0][0] * scale + min;
                String result = "LSTM 预测明日支出：¥" + String.format(Locale.getDefault(), "%.2f", pred);
                
                // 分析建议
                String analysis = generateAnalysis(dailyMap, sorted, pred);
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        tvPrediction.setText(result);
                        tvAnalysis.setText(analysis);
                    });
                }
                
            } catch (Exception e) {
                e.printStackTrace();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        tvPrediction.setText("预测失败：" + e.getMessage());
                        tvAnalysis.setText("请确保模型文件存在于 assets 目录");
                    });
                }
            }
        });
    }
    
    private String generateAnalysis(Map<String, Double> dailyMap, List<String> sorted, float prediction) {
        StringBuilder sb = new StringBuilder();
        
        // 计算最近 7 天平均值
        double recent7Sum = 0;
        int count7 = 0;
        for (int i = sorted.size() - 7; i < sorted.size(); i++) {
            if (i >= 0) {
                recent7Sum += dailyMap.get(sorted.get(i));
                count7++;
            }
        }
        double recent7Avg = count7 > 0 ? recent7Sum / count7 : 0;
        
        // 计算趋势
        double trend = prediction - recent7Avg;
        String trendText = trend > 0 ? "上升" : (trend < 0 ? "下降" : "持平");
        
        sb.append(String.format(Locale.getDefault(), 
            "最近 7 天平均：¥%.2f\n", recent7Avg));
        sb.append(String.format(Locale.getDefault(), 
            "预测趋势：%s %.2f%%\n", trendText, Math.abs(trend) / (recent7Avg > 0 ? recent7Avg : 1) * 100));
        
        // 给出建议
        if (prediction > recent7Avg * 1.2) {
            sb.append("\n⚠️ 预测支出明显高于近期平均，请注意控制消费！");
        } else if (prediction < recent7Avg * 0.8) {
            sb.append("\n✅ 预测支出低于近期平均，继续保持理性消费！");
        } else {
            sb.append("\n📊 预测支出与近期平均相近，保持当前消费习惯即可。");
        }
        
        return sb.toString();
    }
    
    private float readFloatFromAsset(String file) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getContext().getAssets().open(file)))) {
            return Float.parseFloat(br.readLine().trim());
        } catch (Exception e) {
            e.printStackTrace();
            return 0f;
        }
    }
    
    private ByteBuffer loadModelFile() throws Exception {
        try (android.content.res.AssetFileDescriptor fd = getContext().getAssets().openFd("lstm_expense_model.tflite");
             java.io.FileInputStream in = new java.io.FileInputStream(fd.getFileDescriptor())) {
            return in.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    fd.getStartOffset(),
                    fd.getDeclaredLength());
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}
