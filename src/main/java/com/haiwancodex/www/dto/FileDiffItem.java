package com.haiwancodex.www.dto;

import lombok.Data;

@Data
public class FileDiffItem {

    private String pathOrClassName;
    private String fullNewCode;
    private boolean wholeDelete;
}
