package com.contentanalytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generated text report for a completed document analysis.
 *
 * The original upload is deleted after processing, so downloads are built from
 * persisted analysis results instead of reading anything back from storage.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisReportDto {

    private String fileName;
    private String contentType;
    private String content;

}
