package com.pengxh.daily.app.sqlite.bean;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 一次性请假记录。日期使用 yyyy-MM-dd，period 为 ALL_DAY / MORNING / AFTERNOON。
 */
@Entity(tableName = "leave_record_table")
public class LeaveRecordBean {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String startDate;
    private String endDate;
    private String period;
    private String reason;
    private long createdAt;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }
}
