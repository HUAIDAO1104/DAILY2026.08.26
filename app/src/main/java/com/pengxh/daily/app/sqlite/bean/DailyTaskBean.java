package com.pengxh.daily.app.sqlite.bean;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "daily_task_table")
public class DailyTaskBean {
    @PrimaryKey(autoGenerate = true)
    private int id;//主键ID

    private String time;

    private String name;

    private boolean enabled = true;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
