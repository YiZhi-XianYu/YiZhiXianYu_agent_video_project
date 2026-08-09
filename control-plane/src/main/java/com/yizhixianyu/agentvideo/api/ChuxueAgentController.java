package com.yizhixianyu.agentvideo.api;

import com.yizhixianyu.agentvideo.agent.ChuxueAgentService;
import com.yizhixianyu.agentvideo.auth.AuthService;
import com.yizhixianyu.agentvideo.execution.ProxyQuality;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** First Chuxue Agent entry point: user turn -> controlled plan proposal. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chuxue")
public class ChuxueAgentController {
    private final ChuxueAgentService chuxue;
    private final AuthService auth;

    public ChuxueAgentController(ChuxueAgentService chuxue, AuthService auth) {
        this.chuxue = chuxue;
        this.auth = auth;
    }

    @PostMapping("/plan")
    public ChuxueAgentService.Decision plan(@PathVariable String projectId,
                                            @Valid @RequestBody PlanRequest request,
                                            HttpServletRequest servletRequest) {
        var user = auth.requireUser(servletRequest);
        if (!projectId.equals(request.projectId())) {
            throw new IllegalArgumentException("projectId does not match request");
        }
        return chuxue.plan(user.id(), request.sessionId(), request.goal(), request.targetDurationMs(),
            request.quality(), request.assetIds(), request.autoMode());
    }

    public record PlanRequest(
        @NotBlank String projectId,
        @NotBlank String sessionId,
        @NotBlank String goal,
        Integer targetDurationMs,
        @NotNull ProxyQuality quality,
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> assetIds,
        boolean autoMode
    ) {}
}
