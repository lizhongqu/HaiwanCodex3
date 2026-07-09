package com.haiwancodex.www.dto;

import lombok.Data;
import java.util.List;

@Data
public class BatchPatchDTO {
    private List<SinglePatchItem> batchDiffList;
}