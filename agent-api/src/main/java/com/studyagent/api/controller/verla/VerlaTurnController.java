package com.studyagent.api.controller.verla;

import com.studyagent.api.common.Result;
import com.studyagent.api.dto.verla.response.VerlaArtifactVO;
import com.studyagent.api.dto.verla.response.VerlaSessionVO;
import com.studyagent.api.dto.verla.response.VerlaTurnVO;
import com.studyagent.common.api.ApiCode;
import com.studyagent.common.exception.BusinessException;
import com.studyagent.service.application.verla.VerlaConversationService;
import com.studyagent.service.domain.verla.VerlaArtifact;
import com.studyagent.service.domain.verla.VerlaSession;
import com.studyagent.service.domain.verla.VerlaTurn;
import com.studyagent.service.domain.verla.repo.VerlaArtifactRepository;
import com.studyagent.service.domain.verla.repo.VerlaSessionRepository;
import com.studyagent.service.domain.verla.repo.VerlaTurnRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Verla turn / session / artifact 用户面 REST 控制器（PR-13）
 * <p>
 * 详见 docs/verla-Java侧MVP技术方案.md §21.1。\
 * 鉴权：沿用 AuthInterceptor 注入的 clerkUserId；所有 path id 都需经 conversation 反查所有权。\
 */
@Slf4j
@RestController
@RequestMapping("/v1/verla")
@RequiredArgsConstructor
public class VerlaTurnController {

    private final VerlaConversationService conversationService;
    private final VerlaTurnRepository turnRepository;
    private final VerlaSessionRepository sessionRepository;
    private final VerlaArtifactRepository artifactRepository;

    // ========================================================
    // 1) GET /v1/verla/turns/{tid}  ——  turn 详情
    // ========================================================
    @GetMapping("/turns/{tid}")
    public Result<VerlaTurnVO> getTurn(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long tid) {
        ensureLogin(clerkUserId);
        VerlaTurn t = turnRepository.findById(tid);
        if (t == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        // 反查 conversation 鉴权
        conversationService.getOwned(clerkUserId, t.getConversationId());
        return Result.success(VerlaTurnVO.from(t));
    }

    // ========================================================
    // 2) GET /v1/verla/turns/{tid}/sessions  ——  turn 内所有 session
    // ========================================================
    @GetMapping("/turns/{tid}/sessions")
    public Result<List<VerlaSessionVO>> listSessionsOfTurn(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long tid) {
        ensureLogin(clerkUserId);
        VerlaTurn t = turnRepository.findById(tid);
        if (t == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        conversationService.getOwned(clerkUserId, t.getConversationId());
        List<VerlaSession> sessions = sessionRepository.findByTurn(tid);
        return Result.success(sessions.stream()
                .map(VerlaSessionVO::from).collect(Collectors.toList()));
    }

    // ========================================================
    // 3) GET /v1/verla/sessions/{sid}  ——  session 详情
    // ========================================================
    @GetMapping("/sessions/{sid}")
    public Result<VerlaSessionVO> getSession(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long sid) {
        ensureLogin(clerkUserId);
        VerlaSession s = sessionRepository.findById(sid);
        if (s == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        conversationService.getOwned(clerkUserId, s.getConversationId());
        return Result.success(VerlaSessionVO.from(s));
    }

    // ========================================================
    // 4) GET /v1/verla/artifacts/{aid}  ——  卡片 / 材料详情
    // ========================================================
    @GetMapping("/artifacts/{aid}")
    public Result<VerlaArtifactVO> getArtifact(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long aid) {
        ensureLogin(clerkUserId);
        VerlaArtifact a = artifactRepository.findById(aid);
        if (a == null) {
            throw new BusinessException(ApiCode.TASK_NOT_FOUND);
        }
        conversationService.getOwned(clerkUserId, a.getConversationId());
        return Result.success(VerlaArtifactVO.from(a));
    }

    // ========================================================
    // 5) GET /v1/verla/conversations/{cid}/artifacts  ——  conv 维度列表（右栏材料）
    // ========================================================
    @GetMapping("/conversations/{cid}/artifacts")
    public Result<List<VerlaArtifactVO>> listArtifactsOfConversation(
            @RequestAttribute("clerkUserId") String clerkUserId,
            @PathVariable Long cid) {
        ensureLogin(clerkUserId);
        conversationService.getOwned(clerkUserId, cid);
        List<VerlaArtifact> list = artifactRepository.findByConversation(cid);
        return Result.success(list.stream()
                .filter(VerlaArtifactVO::isListVisible)
                .map(VerlaArtifactVO::from).collect(Collectors.toList()));
    }

    // ========================================================
    // helper
    // ========================================================
    private void ensureLogin(String clerkUserId) {
        if (clerkUserId == null || clerkUserId.isBlank()) {
            throw new BusinessException(ApiCode.USER_NOT_LOGGED_IN);
        }
    }
}
