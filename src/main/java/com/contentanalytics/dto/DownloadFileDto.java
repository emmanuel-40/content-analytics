package com.contentanalytics.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.core.io.Resource;

/**
 * Internal transport object for streaming a stored file back to the client.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadFileDto {

    private Resource resource;
    private String fileName;
    private String contentType;
    private Long size;

}