package com.haiwancodex.www.entity;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class CodeChangeFile {
    private Long id;
    private Long batchId;
    private String filePath;
    private String newCode;
    private Integer isDeleteFile;
    private LocalDateTime createdAt;
}