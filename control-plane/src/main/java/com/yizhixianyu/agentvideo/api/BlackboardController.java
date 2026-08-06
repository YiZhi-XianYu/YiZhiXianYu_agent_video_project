package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.agent.BlackboardService;
import com.yizhixianyu.agentvideo.auth.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent-sessions/{sessionId}/blackboard")
public class BlackboardController {
    private final BlackboardService blackboard;
    private final AuthService auth;
    public BlackboardController(BlackboardService blackboard, AuthService auth) { this.blackboard = blackboard; this.auth = auth; }

    @GetMapping
    public BlackboardService.BlackboardView get(@PathVariable String sessionId, HttpServletRequest request) {
        return blackboard.get(auth.requireUser(request).id(), sessionId);
    }

    @PostMapping("/refresh")
    public BlackboardService.BlackboardView refresh(@PathVariable String sessionId,
                                                    @RequestParam(required = false) Long expectedRevision,
                                                    HttpServletRequest request) {
        return blackboard.refresh(auth.requireUser(request).id(), sessionId, expectedRevision);
    }
}
