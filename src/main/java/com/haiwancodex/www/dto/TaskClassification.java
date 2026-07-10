package com.haiwancodex.www.dto;

import com.haiwancodex.www.common.TaskType;
import lombok.Data;

@Data
public class TaskClassification {
    private TaskType taskType;
    private String reasoning;
}
