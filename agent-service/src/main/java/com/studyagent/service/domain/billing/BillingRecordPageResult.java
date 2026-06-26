package com.studyagent.service.domain.billing;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Cursor page for V2 billing records.
 *
 * The cursor is opaque to the frontend; callers should pass nextCursor back to
 * the records endpoint unchanged to load the next page.
 */
@Data
@Builder
public class BillingRecordPageResult {
    private List<BillingRecordResult> items;
    private String nextCursor;
}
