package com.haiwancodex.www.entity;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeChangeFile {
    private Long id;
    private Long batchId;
    private String filePath;
    private String newCode;
    private Integer isDeleteFile;
    private LocalDateTime createdAt;
}