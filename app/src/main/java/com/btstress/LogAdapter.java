package com.btstress;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日志列表适配器
 * 显示每次压测循环的详细日志，颜色区分成功/失败
 */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {

    public static final int TYPE_INFO    = 0;
    public static final int TYPE_SUCCESS = 1;
    public static final int TYPE_FAILURE = 2;
    public static final int TYPE_WARNING = 3;

    private final List<LogEntry> entries = new ArrayList<>();
    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    public static class LogEntry {
        public final String time;
        public final String message;
        public final int type;  // TYPE_INFO / TYPE_SUCCESS / TYPE_FAILURE / TYPE_WARNING

        public LogEntry(String message, int type) {
            this.time = SDF.format(new Date());
            this.message = message;
            this.type = type;
        }
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTime;
        final TextView tvMessage;

        LogViewHolder(View itemView) {
            super(itemView);
            tvTime    = itemView.findViewById(R.id.tv_log_time);
            tvMessage = itemView.findViewById(R.id.tv_log_msg);
        }
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new LogViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogEntry entry = entries.get(position);
        holder.tvTime.setText(entry.time);
        holder.tvMessage.setText(entry.message);

        // 根据日志类型设置颜色
        int color;
        switch (entry.type) {
            case TYPE_SUCCESS: color = Color.parseColor("#4CAF50"); break;  // 绿色
            case TYPE_FAILURE: color = Color.parseColor("#F44336"); break;  // 红色
            case TYPE_WARNING: color = Color.parseColor("#FF9800"); break;  // 橙色
            default:           color = Color.parseColor("#E0E0E0"); break;  // 浅灰
        }
        holder.tvMessage.setTextColor(color);
        holder.tvTime.setTextColor(Color.parseColor("#9E9E9E"));
    }

    @Override
    public int getItemCount() { return entries.size(); }

    /** 添加一条日志（主线程调用） */
    public void addLog(String message, int type) {
        entries.add(new LogEntry(message, type));
        notifyItemInserted(entries.size() - 1);
    }

    /** 清空所有日志 */
    public void clear() {
        entries.clear();
        notifyDataSetChanged();
    }

    /** 最大保留条数（防止OOM） */
    public void trimIfNeeded(int maxSize) {
        if (entries.size() > maxSize) {
            int removeCount = entries.size() - maxSize;
            entries.subList(0, removeCount).clear();
            notifyDataSetChanged();
        }
    }
}
