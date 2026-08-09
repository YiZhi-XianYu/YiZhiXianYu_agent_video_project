package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.agent.BlackboardService;
import com.yizhixianyu.agentvideo.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User-facing, explainable runtime snapshot for Chuxue. */
@RestController
@RequestMapping("/api/v1/agent-sessions/{sessionId}/chuxue")
public class ChuxueRuntimeController {
    private final BlackboardService blackboard;
    private final AuthService auth;

    public ChuxueRuntimeController(BlackboardService blackboard, AuthService auth) {
        this.blackboard = blackboard;
        this.auth = auth;
    }

    @GetMapping("/runtime")
    public BlackboardService.BlackboardView runtime(@PathVariable String sessionId, HttpServletRequest request) {
        return blackboard.refresh(auth.requireUser(request).id(), sessionId, null);
    }
}
