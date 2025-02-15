// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Inc.

package com.starrocks.statistic;

import com.google.gson.annotations.SerializedName;
import com.starrocks.common.io.Text;
import com.starrocks.common.io.Writable;
import com.starrocks.persist.gson.GsonUtils;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.statistic.StatsConstants.AnalyzeType;
import com.starrocks.statistic.StatsConstants.ScheduleStatus;
import com.starrocks.statistic.StatsConstants.ScheduleType;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class AnalyzeJob implements Writable {

    @SerializedName("id")
    private long id;

    @SerializedName("dbId")
    private long dbId;

    @SerializedName("tableId")
    private long tableId;

    // Empty is all column
    @SerializedName("columns")
    private List<String> columns;

    @SerializedName("type")
    private AnalyzeType type;

    @SerializedName("scheduleType")
    private ScheduleType scheduleType;

    @SerializedName("properties")
    private Map<String, String> properties;

    @SerializedName("status")
    private ScheduleStatus status;

    @SerializedName("workTime")
    private LocalDateTime workTime;

    @SerializedName("reason")
    private String reason;

    public AnalyzeJob(long dbId, long tableId, List<String> columns, AnalyzeType type, ScheduleType scheduleType,
                      Map<String, String> properties, ScheduleStatus status, LocalDateTime workTime) {
        this.id = -1;
        this.dbId = dbId;
        this.tableId = tableId;
        this.columns = columns;
        this.type = type;
        this.scheduleType = scheduleType;
        this.properties = properties;
        this.status = status;
        this.workTime = workTime;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getDbId() {
        return dbId;
    }

    public long getTableId() {
        return tableId;
    }

    public List<String> getColumns() {
        return columns;
    }

    public AnalyzeType getAnalyzeType() {
        return type;
    }

    public ScheduleType getScheduleType() {
        return scheduleType;
    }

    public LocalDateTime getWorkTime() {
        return workTime;
    }

    public void setWorkTime(LocalDateTime workTime) {
        this.workTime = workTime;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public ScheduleStatus getStatus() {
        return status;
    }

    public void setStatus(ScheduleStatus status) {
        this.status = status;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void run(StatisticExecutor statisticExecutor) {
        setStatus(StatsConstants.ScheduleStatus.RUNNING);
        GlobalStateMgr.getCurrentAnalyzeMgr().updateAnalyzeJobWithoutLog(this);
        List<StatisticsCollectJob> statisticsCollectJobList =
                StatisticsCollectJobFactory.buildStatisticsCollectJob(this);

        boolean hasFailedCollectJob = false;
        for (StatisticsCollectJob statsJob : statisticsCollectJobList) {
            AnalyzeStatus analyzeStatus = statisticExecutor.collectStatistics(statsJob);
            if (analyzeStatus.getStatus().equals(StatsConstants.ScheduleStatus.FAILED)) {
                setStatus(StatsConstants.ScheduleStatus.FAILED);
                setWorkTime(LocalDateTime.now());
                setReason(analyzeStatus.getReason());
                GlobalStateMgr.getCurrentAnalyzeMgr().updateAnalyzeJobWithLog(this);
                hasFailedCollectJob = true;
                break;
            }
        }

        if (!hasFailedCollectJob) {
            setStatus(StatsConstants.ScheduleStatus.PENDING);
            setWorkTime(LocalDateTime.now());
            GlobalStateMgr.getCurrentAnalyzeMgr().updateAnalyzeJobWithLog(this);
        }
    }

    @Override
    public void write(DataOutput out) throws IOException {
        String s = GsonUtils.GSON.toJson(this);
        Text.writeString(out, s);
    }

    public static AnalyzeJob read(DataInput in) throws IOException {
        String s = Text.readString(in);
        return GsonUtils.GSON.fromJson(s, AnalyzeJob.class);
    }

    @Override
    public String toString() {
        return "AnalyzeJob{" +
                "id=" + id +
                ", dbId=" + dbId +
                ", tableId=" + tableId +
                ", columns=" + columns +
                ", type=" + type +
                ", scheduleType=" + scheduleType +
                ", properties=" + properties +
                ", status=" + status +
                ", workTime=" + workTime +
                ", reason='" + reason + '\'' +
                '}';
    }
}
