package net.nitrogen.ates.core.model;

import com.jfinal.plugin.activerecord.Model;
import net.nitrogen.ates.core.enumeration.ExecResult;
import net.nitrogen.ates.core.enumeration.QueueEntryStatus;

import java.util.*;

public class ExecutionModel extends Model<ExecutionModel> {
    public static final String EXECUTION_COUNT_MAP_KEY_TOTAL = "TOTAL";
    public static final String TABLE = "execution";

    public class Fields {
        public static final String ID = "id";
        public static final String NAME = "name";
        public static final String PROJECT_ID = "project_id";
    }

    public static final ExecutionModel me = new ExecutionModel();

    public long getId() {
        return this.getLong(Fields.ID);
    }

    public void setId(long id) {
        this.set(Fields.ID, id);
    }

    public String getName() {
        return this.getStr(Fields.NAME);
    }

    public void setName(String name) {
        this.set(Fields.NAME, name);
    }

    public long getProjectId() {
        return this.getLong(Fields.PROJECT_ID);
    }

    public void setProjectId(long projectId) {
        this.set(Fields.PROJECT_ID, projectId);
    }

    public List<ExecutionModel> findExecutions(long projectId) {
        return find(
                String.format("SELECT `%s`,`%s`,`%s` FROM `%s` WHERE `%s`=? ORDER BY `%s` DESC", Fields.ID, Fields.NAME, Fields.PROJECT_ID, TABLE, Fields.PROJECT_ID, Fields.ID),
                projectId);
    }

    public long createExecutionByTestGroup(long projectId, String executionName, String env, String jvmOptions, String params, List<Long> testGroupIds) {
        ExecutionModel newExecution = new ExecutionModel();
        newExecution.set(Fields.NAME, executionName).set(Fields.PROJECT_ID, projectId).save();
        long executionId = newExecution.get(Fields.ID);
        Set<String> uniqueTestNames = new HashSet<String>();

        for(Long testGroupId : testGroupIds) {
            for(TestGroupTestCaseModel tg_tc : TestGroupTestCaseModel.me.findTestGroupTestCases(testGroupId.longValue())) {
                uniqueTestNames.add(tg_tc.getTestName());
            }
        }

        List<QueueEntryModel> entries = new ArrayList<QueueEntryModel>();

        for(String testName : uniqueTestNames) {
            QueueEntryModel entry = new QueueEntryModel();
            entry.setStatus(QueueEntryStatus.WAITING.getStatus());
            entry.setName(testName);
            entry.setSlaveName("");
            entry.setExecutionId(executionId);
            entry.setProjectId(projectId);
            entry.setEnv(env);
            entry.setJvmOptions(jvmOptions);
            entry.setParams(params);
            entries.add(entry);
        }

        QueueEntryModel.me.insertEntries(entries);
        return executionId;
    }

    public long cloneExecution(long executionId) {
        ExecutionModel existingExecution = findFirst(String.format("SELECT `%s`,`%s`,`%s` FROM `%s` WHERE `%s`=?", Fields.ID, Fields.NAME, Fields.PROJECT_ID, TABLE, Fields.ID), executionId);
        ExecutionModel newExecution = new ExecutionModel();
        newExecution.set(Fields.NAME, String.format("%s_RerunALL", existingExecution.getName())).set(Fields.PROJECT_ID, existingExecution.getProjectId()).save();
        List<QueueEntryModel> newEntries = new ArrayList<>();

        for(QueueEntryModel entry : QueueEntryModel.me.findEntries(executionId)) {
            QueueEntryModel newEntry = new QueueEntryModel();
            newEntry.setStatus(QueueEntryStatus.WAITING.getStatus());
            newEntry.setName(entry.getName());
            newEntry.setSlaveName("");
            newEntry.setExecutionId(newExecution.getId());
            newEntry.setProjectId(existingExecution.getProjectId());
            newEntry.setEnv(entry.getEnv());
            newEntry.setJvmOptions(entry.getJvmOptions());
            newEntry.setParams(entry.getParams());
            newEntries.add(newEntry);
        }

        QueueEntryModel.me.insertEntries(newEntries);
        return newExecution.getId();
    }

    public long createExecutionByExecResult(long executionId, ExecResult execResult) {
        ExecutionModel existingExecution = findFirst(String.format("SELECT `%s`,`%s`,`%s` FROM `%s` WHERE `%s`=?", Fields.ID, Fields.NAME, Fields.PROJECT_ID, TABLE, Fields.ID), executionId);
        ExecutionModel newExecution = new ExecutionModel();
        newExecution.set(Fields.NAME, String.format("%s_Rerun%s", existingExecution.getName(), execResult.toString())).set(Fields.PROJECT_ID, existingExecution.getProjectId()).save();
        List<QueueEntryModel> newEntries = new ArrayList<>();

        for(QueueEntryModel entry : QueueEntryModel.me.findEntries(executionId, execResult)) {
            QueueEntryModel newEntry = new QueueEntryModel();
            newEntry.setStatus(QueueEntryStatus.WAITING.getStatus());
            newEntry.setName(entry.getName());
            newEntry.setSlaveName("");
            newEntry.setExecutionId(newExecution.getId());
            newEntry.setProjectId(existingExecution.getProjectId());
            newEntry.setEnv(entry.getEnv());
            newEntry.setJvmOptions(entry.getJvmOptions());
            newEntry.setParams(entry.getParams());
            newEntries.add(newEntry);
        }

        QueueEntryModel.me.insertEntries(newEntries);
        return newExecution.getId();
    }

    public Map<String, Map<String, Integer>> passrateOfExecution(long executionId) {
        ExecutionModel execution = findFirst(String.format("SELECT `%s`,`%s`,`%s` FROM `%s` WHERE `%s`=? LIMIT 1", Fields.ID, Fields.NAME, Fields.PROJECT_ID, TABLE, Fields.ID), executionId);
        Map<String, Map<String, Integer>> passrates = new HashMap<>();
        passrates.put(String.format("%d,%s", execution.getId(), execution.getName()), this.executionCountByExecResult(execution.getId()));
        return passrates;
    }

    /**
     *
     * @param projectId
     * @return A map, whose key is a string combination of execution id and name;
     * and value is another map containing proportion of total queue entry count (in this execution) for each result status.
     */
    public Map<String, Map<String, Integer>> passrateOfRecentExecutions(long projectId, int count) {
        List<ExecutionModel> recentExecutions = find(String.format("SELECT `%s`,`%s`,`%s` FROM `%s` WHERE `%s`=? ORDER BY `%s` DESC LIMIT %d", Fields.ID, Fields.NAME, Fields.PROJECT_ID, TABLE, Fields.PROJECT_ID, Fields.ID, count), projectId);
        Map<String, Map<String, Integer>> passrates = new HashMap<>();

        for(ExecutionModel exec : recentExecutions) {
            passrates.put(String.format("%d,%s", exec.getId(), exec.getName()), this.executionCountByExecResult(exec.getId()));
        }

        return passrates;
    }

    private Map<String, Integer> executionCountByExecResult(long executionId) {
        Map<String, Integer> executionCountByExecResult = new HashMap<>();
        List<QueueEntryModel> entries = QueueEntryModel.me.findEntries(executionId);

        int total = entries.size();
        int passed = 0;
        int failed = 0;
        int skipped = 0;
        int unknown = 0;

        for(QueueEntryModel entry : entries) {
            TestResultModel result = TestResultModel.me.findTestResult(entry.getId());

            if(result == null) {
                unknown += 1;
            }else {
                if(result.getExecResult() == ExecResult.PASSED.getValue()) {
                    passed += 1;
                }else if(result.getExecResult() == ExecResult.FAILED.getValue()) {
                    failed += 1;
                }else if(result.getExecResult() == ExecResult.SKIPPED.getValue()) {
                    skipped += 1;
                }else {
                    unknown += 1;
                }
            }
        }

        executionCountByExecResult.put(ExecResult.PASSED.toString(), passed);
        executionCountByExecResult.put(ExecResult.FAILED.toString(), failed);
        executionCountByExecResult.put(ExecResult.SKIPPED.toString(), skipped);
        executionCountByExecResult.put(ExecResult.UNKNOWN.toString(), unknown);
        executionCountByExecResult.put(EXECUTION_COUNT_MAP_KEY_TOTAL, total);

        return executionCountByExecResult;
    }
}
