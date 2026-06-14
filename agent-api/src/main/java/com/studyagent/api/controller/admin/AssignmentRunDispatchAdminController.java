package com.studyagent.api.controller.admin;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.admin.response.AssignmentRunDispatchMonitorVO;
import com.studyagent.service.application.verla.VerlaTurnOrchestrator;
import com.studyagent.service.application.verla.admin.AssignmentRunDispatchMonitorService;
import com.studyagent.service.application.verla.admin.VerlaAdminAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops console: assignment run dispatch gate + recent workforce tasks.
 */
@Slf4j
@RestController
@RequestMapping("/v1/admin/assignment-run-dispatch")
@RequiredArgsConstructor
public class AssignmentRunDispatchAdminController {

    private final VerlaAdminAccessService adminAccessService;
    private final AssignmentRunDispatchMonitorService monitorService;
    private final VerlaTurnOrchestrator turnOrchestrator;

    @GetMapping("/monitor")
    public Result<AssignmentRunDispatchMonitorVO> getMonitor(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        adminAccessService.assertAdmin(clerkUserId);
        log.debug("[admin/assignment-run-dispatch] monitor requested by {}", clerkUserId);
        return Result.success(AssignmentRunDispatchMonitorVO.from(
                monitorService.getMonitor(limit)));
    }

    @GetMapping("/access")
    public Result<Boolean> checkAccess(@RequestAttribute("clerkUserId") String clerkUserId) {
        return Result.success(adminAccessService.isAdmin(clerkUserId));
    }

    /**
     * 终止指定 session 的 assignment run（下发 cmd.agent.control.cancel）。
     */
    @PostMapping("/{sessionId}/cancel")
    public Result<Boolean> cancel(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable("sessionId") Long sessionId) {
        adminAccessService.assertAdmin(clerkUserId);
        log.info("[admin/assignment-run-dispatch] cancel session={} requested by {}",
                sessionId, clerkUserId);
        turnOrchestrator.cancelAssignmentRun(sessionId);
        return Result.success(Boolean.TRUE);
    }
}
