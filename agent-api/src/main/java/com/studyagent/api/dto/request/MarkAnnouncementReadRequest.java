package com.studyagent.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class MarkAnnouncementReadRequest {

    @NotEmpty
    private List<@NotBlank String> ids;
}
