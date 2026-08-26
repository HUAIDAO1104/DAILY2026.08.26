package com.pengxh.daily.app.sqlite.bean;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "execution_record_table")
public class ExecutionRecordBean {
    @PrimaryKey(autoGenerate = true)
    private int id;
    private String date;
    private int taskId;
    private String taskName;
    private String plannedTime;
    private String actualTime;
    private String status;
    private String detail;
    private long createdAt;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public int getTaskId() { return taskId; }
    public void setTaskId(int taskId) { this.taskId = taskId; }
    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }
    public String getPlannedTime() { return plannedTime; }
    public void setPlannedTime(String plannedTime) { this.plannedTime = plannedTime; }
    public String getActualTime() { return actualTime; }
    public void setActualTime(String actualTime) { this.actualTime = actualTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
